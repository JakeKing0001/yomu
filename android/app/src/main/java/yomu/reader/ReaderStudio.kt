package yomu.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.math.max

@Immutable
class StudioFrame(val image: ImageBitmap, val panels: List<Rect>)

/** Page tools that work the same over every viewer, because they draw above it. */
@Stable
class ReaderStudioState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: YomuPreferences,
    private val translator: PageTranslator,
    private val enhancer: PageEnhancer,
    private val aiEnhancer: AiEnhancer,
    private val readingMemory: ReadingMemory,
    private val viewer: () -> Viewer?,
    private val isRightToLeft: () -> Boolean,
    private val sourceLanguage: () -> String?,
    private val mangaId: () -> Long?,
) {
    var panelMode by mutableStateOf(preferences.panelMode.get())
        private set
    var translate by mutableStateOf(preferences.translatePages.get())
        private set
    var enhance by mutableStateOf(preferences.enhancePages.get())
        private set
    var aiEnhance by mutableStateOf(preferences.aiEnhance.get())
        private set

    /** How far the AI is through the current page, while it's working on it. */
    var aiProgress by mutableStateOf<Float?>(null)
        private set
    var ambientEnabled by mutableStateOf(preferences.ambientGlass.get())
        private set
    var memoryEnabled by mutableStateOf(preferences.readingMemory.get())
        private set
    var autoScroll by mutableStateOf(false)
        private set
    var autoScrollPaused by mutableStateOf(false)
    var speed by mutableStateOf(preferences.autoScrollSpeed.get())
        private set

    var ambient by mutableStateOf<Color?>(null)
        private set
    var frame by mutableStateOf<StudioFrame?>(null)
        private set
    var panelIndex by mutableIntStateOf(0)
        private set
    var isWorking by mutableStateOf(false)
        private set

    /** The viewer shows through while it's on a chapter transition, which has no page to process. */
    private var isOnTransition by mutableStateOf(false)

    val isOverlayVisible: Boolean
        get() = (panelMode || translate || enhance) && !isOnTransition && frame != null

    val currentPanel: Rect?
        get() = frame?.panels?.getOrNull(panelIndex)

    var isTouching = false

    /** Days since this series was last read, when that's long enough to offer a recap. */
    var welcomeBackDays by mutableStateOf<Int?>(null)
        private set
    private var hasCheckedWelcomeBack = false

    val currentPage: ReaderPage? get() = page

    private var page: ReaderPage? = null
    private var isLastPage = false
    private var pageShownAt = 0L
    private var density = DEFAULT_DENSITY
    private var job: Job? = null
    private var memoryJob: Job? = null
    private var aheadJob: Job? = null
    private val rendered = object : android.util.LruCache<String, RenderedPage>(MAX_CACHED_PAGES) {}

    private class RenderedPage(val bitmap: Bitmap, val panels: List<Rect>)

    fun onPageChanged(newPage: ReaderPage, isLast: Boolean) {
        val previous = page
        if (previous === newPage) return
        learnPace(previous, newPage)

        val startAtEnd = previous != null &&
            previous.chapter === newPage.chapter &&
            newPage.index < previous.index
        page = newPage
        isLastPage = isLast
        isOnTransition = false
        render(newPage, startAtEnd)
    }

    fun togglePanelMode() {
        panelMode = !panelMode
        preferences.panelMode.set(panelMode)
        refresh()
    }

    fun toggleTranslate() {
        translate = !translate
        preferences.translatePages.set(translate)
        refresh()
    }

    fun toggleEnhance() {
        enhance = !enhance
        preferences.enhancePages.set(enhance)
        refresh()
    }

    fun toggleAiEnhance() {
        aiEnhance = !aiEnhance
        preferences.aiEnhance.set(aiEnhance)
        refresh()
    }

    fun toggleAmbient() {
        ambientEnabled = !ambientEnabled
        preferences.ambientGlass.set(ambientEnabled)
        if (!ambientEnabled) ambient = null else refresh()
    }

    fun toggleMemory() {
        memoryEnabled = !memoryEnabled
        preferences.readingMemory.set(memoryEnabled)
        if (memoryEnabled) refresh()
    }

    fun toggleAutoScroll() {
        autoScroll = !autoScroll
        autoScrollPaused = false
    }

    fun changeSpeed(delta: Float) {
        speed = (speed + delta).coerceIn(MIN_SPEED, MAX_SPEED)
        preferences.autoScrollSpeed.set(speed)
    }

    private fun refresh() {
        rendered.evictAll()
        page?.let { render(it, startAtEnd = false) }
    }

    private fun render(target: ReaderPage, startAtEnd: Boolean) {
        job?.cancel()
        aheadJob?.cancel()
        job = scope.launch(Dispatchers.Default) {
            try {
                PageAnalyzer.decode(target, ANALYSIS_SIDE)?.let { small ->
                    density = PageAnalyzer.inkDensity(small)
                    if (ambientEnabled) {
                        val color = PageAnalyzer.ambientColor(small)
                        withContext(Dispatchers.Main) { ambient = color?.let(::Color) }
                    }
                    small.recycle()
                }
                rememberPage(target)

                if (!panelMode && !translate && !enhance) {
                    withContext(Dispatchers.Main) { frame = null }
                    return@launch
                }

                withContext(Dispatchers.Main) { isWorking = true }
                val tools = "$panelMode:$translate:$enhance:$aiEnhance:${isRightToLeft()}"
                val key = "${target.chapter.chapter.id}:${target.index}:$tools"
                val result = rendered.get(key) ?: processPage(target)?.also { rendered.put(key, it) }
                withContext(Dispatchers.Main) {
                    isWorking = false
                    if (result == null) {
                        frame = null
                    } else {
                        panelIndex = if (startAtEnd) result.panels.lastIndex else 0
                        frame = StudioFrame(result.bitmap.asImageBitmap(), result.panels)
                    }
                }
                if (enhance && aiEnhance) prepareAhead(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Studio couldn't process the page" }
                withContext(Dispatchers.Main) {
                    isWorking = false
                    aiProgress = null
                    frame = null
                    context.toast(MR.strings.yomu_studio_error)
                }
            }
        }
    }

    private suspend fun processPage(target: ReaderPage): RenderedPage? {
        var bitmap = PageAnalyzer.decode(target, PAGE_SIDE) ?: return null
        // The model works from the clean page: it would otherwise redraw translated text as if it were art.
        val usesAi = enhance && aiEnhance && aiEnhancer.canEnhance(bitmap)
        if (usesAi) {
            val original = bitmap
            try {
                bitmap = aiEnhancer.enhance(original, aiKey(target)) { done ->
                    scope.launch(Dispatchers.Main) { aiProgress = done }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { aiProgress = null }
            }
            original.recycle()
        }
        val panels = if (panelMode) PageAnalyzer.panels(bitmap, isRightToLeft()) else listOf(FULL_PAGE)
        if (translate) {
            try {
                bitmap = translator.translate(bitmap, sourceLanguage())
            } catch (_: PageTranslator.AlreadyItalianException) {
                withContext(Dispatchers.Main) {
                    translate = false
                    preferences.translatePages.set(false)
                    context.toast(MR.strings.yomu_studio_already_italian)
                }
            }
        }
        if (enhance && !usesAi) bitmap = enhancer.enhance(bitmap)
        return RenderedPage(bitmap, panels)
    }

    private fun aiKey(target: ReaderPage) = "${target.chapter.chapter.id}_${target.index}"

    /** The model takes a while per page, so the next ones are made ready while this one is being read. */
    private fun prepareAhead(current: ReaderPage) {
        aheadJob = scope.launch(Dispatchers.Default) {
            current.chapter.pages.orEmpty()
                .filter { it.index in current.index + 1..current.index + AI_PAGES_AHEAD }
                .filter { it.status == Page.State.Ready && !aiEnhancer.isCached(aiKey(it)) }
                .forEach { next ->
                    val bitmap = PageAnalyzer.decode(next, PAGE_SIDE) ?: return@forEach
                    if (aiEnhancer.canEnhance(bitmap)) aiEnhancer.enhance(bitmap, aiKey(next)) {}.recycle()
                    bitmap.recycle()
                }
        }
    }

    /** Only pages actually opened are remembered, which is what keeps summaries free of spoilers. */
    private fun rememberPage(target: ReaderPage) {
        if (!memoryEnabled) return
        val manga = mangaId() ?: return
        memoryJob?.cancel()
        memoryJob = scope.launch(Dispatchers.Default) {
            delay(MEMORY_DWELL_MS)
            readPage(manga, target)
        }
    }

    private suspend fun readPage(manga: Long, target: ReaderPage) {
        val chapter = target.chapter.chapter
        val bitmap = PageAnalyzer.decode(target, MEMORY_SIDE) ?: return
        val text = try {
            translator.recognize(bitmap, sourceLanguage())
        } finally {
            bitmap.recycle()
        }
        readingMemory.add(manga, chapter.id, chapter.chapterNumber, target.index, text)
    }

    /** Reads the pages of this chapter opened so far that memory missed, so a recap works straight away. */
    suspend fun catchUpMemory(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.Default) {
        val manga = mangaId() ?: return@withContext
        val current = page ?: return@withContext
        val known = readingMemory.pagesOf(manga, current.chapter.chapter.id)
        val missing = current.chapter.pages.orEmpty()
            .filter { it.index <= current.index && it.index !in known && it.status == Page.State.Ready }
            .takeLast(MAX_CATCH_UP_PAGES)
        missing.forEachIndexed { index, target ->
            onProgress(index + 1, missing.size)
            try {
                readPage(manga, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Couldn't read page ${target.number}" }
            }
        }
    }

    suspend fun checkWelcomeBack(lastReadAt: Long?, chapterNumber: Double, pageIndex: Int) {
        if (hasCheckedWelcomeBack) return
        hasCheckedWelcomeBack = true
        if (!preferences.welcomeBack.get() || lastReadAt == null) return
        val days = ((System.currentTimeMillis() - lastReadAt) / DAY_MS).toInt()
        if (days < WELCOME_BACK_DAYS) return
        val manga = mangaId() ?: return
        // A recap needs something read: remembered pages, or pages before this one to read now.
        if (pageIndex > 0 || readingMemory.chaptersUpTo(manga, chapterNumber, pageIndex).isNotEmpty()) {
            welcomeBackDays = days
        }
    }

    fun dismissWelcomeBack() {
        welcomeBackDays = null
    }

    fun showPanel(index: Int) {
        val panels = frame?.panels ?: return
        if (isOverlayVisible && index in panels.indices) panelIndex = index
    }

    /** Moves past [from]; a chapter end shows a transition first, and webtoons take several scrolls per page. */
    suspend fun turnPage(from: ReaderPage): Boolean {
        repeat(MAX_TURN_STEPS) {
            if (isLastPage && isOverlayVisible) isOnTransition = true
            viewer()?.moveForward()
            delay(TURN_WAIT_MS)
            if (page !== from) return true
        }
        return false
    }

    suspend fun pageForPicking(): Pair<Bitmap, List<Rect>>? = withContext(Dispatchers.Default) {
        val target = page ?: return@withContext null
        val bitmap = PageAnalyzer.decode(target, PAGE_SIDE) ?: return@withContext null
        bitmap to PageAnalyzer.panels(bitmap, isRightToLeft())
    }

    fun forward() {
        val current = frame
        if (current != null && isOverlayVisible && panelIndex < current.panels.lastIndex) {
            panelIndex++
            return
        }
        if (isLastPage && isOverlayVisible) isOnTransition = true
        viewer()?.moveForward()
    }

    fun backward() {
        if (isOverlayVisible && panelIndex > 0) {
            panelIndex--
            return
        }
        viewer()?.moveBackward()
    }

    /** Runs until cancelled, moving at the reader's own learned pace. */
    suspend fun runAutoScroll(viewportHeight: Int) {
        var lastFrame = 0L
        var carry = 0f
        var dwell = 0f
        while (true) {
            if (autoScrollPaused || isTouching) {
                lastFrame = 0L
                delay(PAUSE_POLL_MS)
                continue
            }
            val now = withFrameNanos { it }
            if (lastFrame == 0L) {
                lastFrame = now
                continue
            }
            val seconds = (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            val pageSeconds = currentPageSeconds()

            if (!isOverlayVisible) {
                carry += viewportHeight / pageSeconds * seconds
                val pixels = carry.toInt()
                carry -= pixels
                if (pixels > 0 && viewer()?.autoScrollBy(pixels) == true) continue
                if (pixels == 0) continue
            }

            dwell += seconds
            val perStep = if (isOverlayVisible) pageSeconds / max(1, frame?.panels?.size ?: 1) else pageSeconds
            if (dwell >= perStep) {
                dwell = 0f
                if (isLastPage && panelIndex >= (frame?.panels?.lastIndex ?: 0)) {
                    autoScroll = false
                    return
                }
                forward()
            }
        }
    }

    private fun currentPageSeconds(): Float {
        return max(MIN_PAGE_SECONDS, preferences.pageSeconds.get() * densityFactor(density) / speed)
    }

    private fun learnPace(previous: ReaderPage?, next: ReaderPage) {
        val now = System.currentTimeMillis()
        val elapsed = (now - pageShownAt) / 1000f
        pageShownAt = now
        val turnedOnePage = previous != null && previous.chapter === next.chapter && next.index == previous.index + 1
        if (autoScroll || !turnedOnePage || elapsed !in MIN_LEARN_SECONDS..MAX_LEARN_SECONDS) return

        val normalized = elapsed / densityFactor(density)
        val learned = preferences.pageSeconds.get() * (1 - LEARN_RATE) + normalized * LEARN_RATE
        preferences.pageSeconds.set(learned.coerceIn(MIN_PAGE_SECONDS, MAX_LEARN_SECONDS))
    }

    private fun densityFactor(value: Float) = 0.55f + value * 1.6f

    companion object {
        val FULL_PAGE = Rect(0f, 0f, 1f, 1f)
        private const val ANALYSIS_SIDE = 480
        private const val PAGE_SIDE = 2200
        private const val MEMORY_SIDE = 2400
        private const val MAX_CATCH_UP_PAGES = 60
        private const val AI_PAGES_AHEAD = 2
        private const val MEMORY_DWELL_MS = 1500L
        private const val MAX_CACHED_PAGES = 6
        private const val DEFAULT_DENSITY = 0.28f
        private const val MIN_SPEED = 0.4f
        private const val MAX_SPEED = 3f
        private const val MIN_PAGE_SECONDS = 2.5f
        private const val MIN_LEARN_SECONDS = 1.5f
        private const val MAX_LEARN_SECONDS = 90f
        private const val LEARN_RATE = 0.12f
        private const val PAUSE_POLL_MS = 90L
        private const val DAY_MS = 86_400_000L
        private const val WELCOME_BACK_DAYS = 3
        private const val MAX_TURN_STEPS = 6
        private const val TURN_WAIT_MS = 900L
    }
}
