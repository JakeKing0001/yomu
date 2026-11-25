package yomu.reader

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Reads the balloons aloud in panel order and turns the page by itself. */
@Stable
class ReaderVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: YomuPreferences,
    private val translator: PageTranslator,
    private val studio: ReaderStudioState,
    private val sourceLanguage: () -> String?,
    private val isRightToLeft: () -> Boolean,
) {
    var isOn by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var caption by mutableStateOf<String?>(null)
        private set
    var rate by mutableFloatStateOf(preferences.voiceRate.get())
        private set

    private var engine: TextToSpeech? = null
    private var job: Job? = null
    private var finished: ((Boolean) -> Unit)? = null

    fun toggle() {
        if (isOn) stop() else start()
    }

    private fun start() {
        isOn = true
        isPaused = false
        isPreparing = true
        scope.launch {
            val ready = engine ?: createEngine()
            if (ready == null) {
                context.toast(MR.strings.yomu_voice_unavailable)
                stop()
                return@launch
            }
            engine = ready
            studio.currentPage?.let(::readFrom)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine?.stop()
        isOn = false
        isPaused = false
        isPreparing = false
        caption = null
    }

    fun togglePause() {
        isPaused = !isPaused
        // The engine can't resume mid-sentence, so the interrupted balloon is read again.
        if (isPaused) engine?.stop()
    }

    fun pauseForBackground() {
        if (isOn && !isPaused) togglePause()
    }

    fun changeRate(delta: Float) {
        rate = (rate + delta).coerceIn(MIN_RATE, MAX_RATE)
        preferences.voiceRate.set(rate)
        engine?.setSpeechRate(rate)
    }

    fun onPageChanged(page: ReaderPage) {
        if (isOn && engine != null) readFrom(page)
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
    }

    private fun readFrom(page: ReaderPage) {
        job?.cancel()
        engine?.stop()
        job = scope.launch {
            try {
                isPreparing = true
                caption = null
                val lines = prepare(page)
                isPreparing = false
                configureLanguage()
                lines.forEach { line ->
                    studio.showPanel(line.panel)
                    caption = line.text
                    speakUntilDone(line.text)
                }
                caption = null
                delay(PAGE_PAUSE_MS)
                if (!studio.turnPage(page)) {
                    context.toast(MR.strings.yomu_voice_finished)
                    stop()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Couldn't read the page aloud" }
                context.toast(MR.strings.yomu_voice_error)
                stop()
            }
        }
    }

    private suspend fun prepare(page: ReaderPage): List<SpokenLine> = withContext(Dispatchers.Default) {
        val bitmap: Bitmap = PageAnalyzer.decode(page, VOICE_SIDE) ?: return@withContext emptyList()
        try {
            val panels = PageAnalyzer.panels(bitmap, isRightToLeft())
            val lines = translator.spokenLines(bitmap, sourceLanguage(), studio.translate)
            lines.mapNotNull { line ->
                val text = RecapText.cleanPage(line.text).joinToString(" ").takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val center = Offset(line.centerX / bitmap.width, line.centerY / bitmap.height)
                val panel = panels.indexOfFirst { it.contains(center) }.takeIf { it >= 0 } ?: panels.lastIndex
                SpokenLine(text, panel, line.centerY)
            }.sortedWith(compareBy(SpokenLine::panel, SpokenLine::top))
        } finally {
            bitmap.recycle()
        }
    }

    private fun configureLanguage() {
        val code = if (studio.translate) "it" else sourceLanguage()?.substringBefore('-') ?: "it"
        val locale = Locale.forLanguageTag(code)
        val tts = engine ?: return
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        tts.setSpeechRate(rate)
    }

    private suspend fun speakUntilDone(text: String) {
        while (true) {
            if (isPaused) snapshotFlow { isPaused }.first { !it }
            val completed = speak(text)
            if (completed || !isPaused) return
        }
    }

    private suspend fun speak(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val tts = engine
        if (tts == null) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }
        finished = { completed -> if (continuation.isActive) continuation.resume(completed) }
        continuation.invokeOnCancellation { tts.stop() }
        val result = tts.speak(
            text.take(TextToSpeech.getMaxSpeechInputLength()),
            TextToSpeech.QUEUE_FLUSH,
            null,
            UUID.randomUUID().toString(),
        )
        if (result != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(true)
    }

    private suspend fun createEngine(): TextToSpeech? = suspendCancellableCoroutine { continuation ->
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            val tts = created
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            finished?.invoke(true)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            finished?.invoke(true)
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            finished?.invoke(false)
                        }
                    },
                )
                if (continuation.isActive) continuation.resume(tts)
            } else {
                tts?.shutdown()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private class SpokenLine(val text: String, val panel: Int, val top: Float)

    private companion object {
        const val VOICE_SIDE = 2400
        const val PAGE_PAUSE_MS = 500L
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.8f
    }
}
