package yomu.panels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.withClip
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
data class SavedPanel(
    val id: String,
    val mangaId: Long,
    val title: String,
    val chapter: String,
    val page: Int,
    val savedAt: Long,
    val width: Int,
    val height: Int,
)

/** Favourite panels, kept as images on the device so they outlive the source and the chapter cache. */
@Inject
@SingleIn(AppScope::class)
class PanelCollection(private val context: Context) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val folder get() = File(context.filesDir, "yomu_panels").apply { mkdirs() }
    private val index get() = File(folder, "index.json")

    private val _panels = MutableStateFlow<List<SavedPanel>>(emptyList())
    val panels: StateFlow<List<SavedPanel>> = _panels.asStateFlow()
    private var isLoaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isLoaded) return@withLock
            _panels.value = runCatching {
                json.decodeFromString<List<SavedPanel>>(index.readText())
            }.getOrDefault(emptyList())
            isLoaded = true
        }
    }

    fun file(panel: SavedPanel) = File(folder, "${panel.id}.jpg")

    suspend fun save(
        page: Bitmap,
        area: Rect,
        mangaId: Long,
        title: String,
        chapter: String,
        pageNumber: Int,
    ): SavedPanel = withContext(Dispatchers.IO) {
        load()
        val left = (area.left * page.width).roundToInt().coerceIn(0, page.width - 1)
        val top = (area.top * page.height).roundToInt().coerceIn(0, page.height - 1)
        val width = (area.width * page.width).roundToInt().coerceIn(1, page.width - left)
        val height = (area.height * page.height).roundToInt().coerceIn(1, page.height - top)
        val crop = Bitmap.createBitmap(page, left, top, width, height)
        val panel = SavedPanel(
            id = UUID.randomUUID().toString(),
            mangaId = mangaId,
            title = title,
            chapter = chapter,
            page = pageNumber,
            savedAt = System.currentTimeMillis(),
            width = width,
            height = height,
        )
        file(panel).outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (crop !== page) crop.recycle()
        mutex.withLock {
            _panels.value = listOf(panel) + _panels.value
            writeIndex()
        }
        panel
    }

    suspend fun delete(panel: SavedPanel) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(panel).delete()
            _panels.value = _panels.value.filterNot { it.id == panel.id }
            writeIndex()
        }
    }

    /** A framed image with the series and chapter, ready to post. */
    suspend fun shareCard(panel: SavedPanel, chapterLabel: String): File? = withContext(Dispatchers.IO) {
        val image = BitmapFactory.decodeFile(file(panel).path) ?: return@withContext null
        val inner = CARD_WIDTH - CARD_MARGIN * 2
        val scale = min(inner / image.width.toFloat(), MAX_IMAGE_HEIGHT / image.height.toFloat())
        val width = (image.width * scale).roundToInt()
        val height = (image.height * scale).roundToInt()
        val card = Bitmap.createBitmap(CARD_WIDTH, height + CARD_MARGIN + FOOTER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(CARD_BACKGROUND)
        val destination = RectF(
            (CARD_WIDTH - width) / 2f,
            CARD_MARGIN.toFloat(),
            (CARD_WIDTH + width) / 2f,
            CARD_MARGIN + height.toFloat(),
        )
        val clip = Path().apply { addRoundRect(destination, CARD_RADIUS, CARD_RADIUS, Path.Direction.CW) }
        canvas.withClip(clip) {
            drawBitmap(image, null, destination, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        image.recycle()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD_ACCENT
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val baseline = height + CARD_MARGIN + 76f
        val title = TextUtils.ellipsize(panel.title, titlePaint, inner.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, CARD_MARGIN.toFloat(), baseline, titlePaint)
        canvas.drawText("$chapterLabel · Yomu", CARD_MARGIN.toFloat(), baseline + 52f, detailPaint)

        val output = File(File(context.cacheDir, "yomu_share").apply { mkdirs() }, "panel_${panel.id}.jpg")
        output.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        card.recycle()
        output
    }

    private fun writeIndex() {
        index.writeText(json.encodeToString(_panels.value))
    }

    private companion object {
        const val JPEG_QUALITY = 92
        const val CARD_WIDTH = 1080
        const val CARD_MARGIN = 48
        const val FOOTER_HEIGHT = 170
        const val MAX_IMAGE_HEIGHT = 1500f
        const val CARD_RADIUS = 36f
        const val CARD_BACKGROUND = 0xFF0B0B0F.toInt()
        const val CARD_ACCENT = 0xFF5AA9FF.toInt()
    }
}
