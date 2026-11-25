package yomu.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withTranslation
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** OCR and translation run on the device; only the language packs are downloaded, once. */
@Inject
@SingleIn(AppScope::class)
class PageTranslator {

    private val recognizers = HashMap<String, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val mutex = Mutex()

    /** Reads the words on a page without translating them. */
    suspend fun recognize(bitmap: Bitmap, language: String?): String = withContext(Dispatchers.Default) {
        val family = scriptFamily(language)
        val result = mutex.withLock { recognizer(family) }.process(InputImage.fromBitmap(bitmap, 0)).await()
        val rowHeight = max(1, bitmap.height / READING_ROWS)
        mergeIntoBubbles(textAreas(result, family), family)
            // Manga read top to bottom and right to left, and OCR returns blocks in no useful order.
            .sortedWith(compareBy<TextArea> { it.box.top / rowHeight }.thenByDescending { it.box.right })
            .joinToString("\n") { it.text }
    }

    /** The balloons of a page with their position, in the reader's language when [translate] is on. */
    suspend fun spokenLines(bitmap: Bitmap, language: String?, translate: Boolean): List<PageLine> =
        withContext(Dispatchers.Default) {
            val family = scriptFamily(language)
            val source = sourceLanguage(language).takeIf { translate }
            val (recognizer, translator) = mutex.withLock { recognizer(family) to source?.let(::translator) }
            translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            mergeIntoBubbles(textAreas(result, family), family).map { area ->
                PageLine(
                    text = translator?.translate(area.text)?.await() ?: area.text,
                    centerX = area.box.exactCenterX(),
                    centerY = area.box.exactCenterY(),
                )
            }
        }

    /** Returns a copy of [bitmap] with its text replaced by Italian. */
    suspend fun translate(bitmap: Bitmap, language: String?): Bitmap = withContext(Dispatchers.Default) {
        val source = sourceLanguage(language) ?: throw AlreadyItalianException()
        val family = scriptFamily(language)
        val (recognizer, translator) = mutex.withLock { recognizer(family) to translator(source) }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()

        val blocks = textAreas(recognizer.process(InputImage.fromBitmap(bitmap, 0)).await(), family)
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        mergeIntoBubbles(blocks, family).forEach { area ->
            drawBubble(canvas, bitmap, area, translator.translate(area.text).await())
        }
        output
    }

    private fun recognizer(family: String) = recognizers.getOrPut(family) {
        TextRecognition.getClient(
            when (family) {
                "ja" -> JapaneseTextRecognizerOptions.Builder().build()
                "ko" -> KoreanTextRecognizerOptions.Builder().build()
                "zh" -> ChineseTextRecognizerOptions.Builder().build()
                else -> TextRecognizerOptions.DEFAULT_OPTIONS
            },
        )
    }

    private fun translator(source: String) = translators.getOrPut(source) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.ITALIAN)
                .build(),
        )
    }

    private fun scriptFamily(language: String?): String {
        return when (language?.substringBefore('-')?.lowercase()) {
            "ja" -> "ja"
            "ko" -> "ko"
            "zh" -> "zh"
            else -> "latin"
        }
    }

    private fun sourceLanguage(language: String?): String? {
        val code = language?.substringBefore('-')?.lowercase()
        if (code == "it") return null
        return code?.let(TranslateLanguage::fromLanguageTag) ?: TranslateLanguage.ENGLISH
    }

    private fun normalize(text: String, family: String): String {
        val separator = if (family == "latin" || family == "ko") " " else ""
        return text.lines().joinToString(separator) { it.trim() }.trim()
    }

    private fun textAreas(result: Text, family: String): List<TextArea> {
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = normalize(block.text, family)
            val lineHeight = block.lines.mapNotNull { it.boundingBox?.height() }.average()
            TextArea(box, text, if (lineHeight.isNaN()) box.height().toFloat() else lineHeight.toFloat())
                .takeIf { text.length >= 2 }
        }
    }

    /** OCR often returns one bubble as several blocks, and translating the fragments alone reads badly. */
    private fun mergeIntoBubbles(blocks: List<TextArea>, family: String): List<TextArea> {
        val areas = blocks.toMutableList()
        val separator = if (family == "latin" || family == "ko") " " else ""
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in areas.indices) {
                for (j in i + 1 until areas.size) {
                    val a = areas[i]
                    val b = areas[j]
                    if (!belongTogether(a, b, family)) continue
                    // Vertical Japanese and Chinese columns read right to left.
                    val isColumn = family != "latin" && family != "ko" && a.box.height() > a.box.width() * 1.4f
                    val aFirst = if (isColumn) a.box.centerX() >= b.box.centerX() else a.box.top <= b.box.top
                    val (first, second) = if (aFirst) a to b else b to a
                    areas[i] = TextArea(
                        Rect(a.box).apply { union(b.box) },
                        first.text + separator + second.text,
                        (a.lineHeight + b.lineHeight) / 2,
                    )
                    areas.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
        return areas
    }

    private fun belongTogether(a: TextArea, b: TextArea, family: String): Boolean {
        val line = (a.lineHeight + b.lineHeight) / 2
        val overlapX = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
        val gapY = max(a.box.top, b.box.top) - min(a.box.bottom, b.box.bottom)
        if (overlapX >= min(a.box.width(), b.box.width()) * 0.4f && gapY <= line * 0.9f) return true
        if (family == "latin" || family == "ko") return false
        val overlapY = min(a.box.bottom, b.box.bottom) - max(a.box.top, b.box.top)
        val gapX = max(a.box.left, b.box.left) - min(a.box.right, b.box.right)
        return overlapY >= min(a.box.height(), b.box.height()) * 0.4f && gapX <= min(a.box.width(), b.box.width())
    }

    private fun drawBubble(canvas: Canvas, source: Bitmap, area: TextArea, text: String) {
        val box = area.box
        val pad = max(3, (area.lineHeight * 0.22f).roundToInt())
        val erase = Rect(
            max(0, box.left - pad),
            max(0, box.top - pad),
            min(source.width, box.right + pad),
            min(source.height, box.bottom + pad),
        )
        if (erase.width() < 6 || erase.height() < 6) return

        val paper = paperColor(source, erase)
        canvas.drawRect(erase, Paint().apply { color = paper })

        // Italian is longer than the original, so the text may use the free paper around it.
        val room = growOverPaper(source, erase, paper)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (Color.luminance(paper) < 0.45f) Color.WHITE else Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = max(1, room.width() - pad)
        val height = max(1, room.height() - pad)
        var size = max(source.width * 0.034f, MIN_TEXT_SIZE)
        var layout = layoutFor(text, paint, size, width)
        val longestWord = text.split(' ').maxBy(String::length)
        while ((layout.height > height || paint.measureText(longestWord) > width) && size > MIN_TEXT_SIZE) {
            size *= 0.93f
            layout = layoutFor(text, paint, size, width)
        }
        canvas.withTranslation(
            room.left + (room.width() - width) / 2f,
            room.top + max(0, room.height() - layout.height) / 2f,
        ) {
            layout.draw(this)
        }
    }

    private fun growOverPaper(bitmap: Bitmap, start: Rect, paper: Int): Rect {
        val room = Rect(start)
        val maxGrowX = max(start.width() / 2, (bitmap.width * 0.06f).roundToInt())
        val maxGrowY = max(start.height() / 3, (bitmap.height * 0.025f).roundToInt())
        val floorLeft = max(0, start.left - maxGrowX)
        val ceilRight = min(bitmap.width, start.right + maxGrowX)
        val floorTop = max(0, start.top - maxGrowY)
        val ceilBottom = min(bitmap.height, start.bottom + maxGrowY)
        val step = 2
        var growing = true
        while (growing) {
            growing = false
            if (room.left - step >= floorLeft && isPaperColumn(bitmap, room.left - step, room, paper)) {
                room.left -= step
                growing = true
            }
            if (room.right + step <= ceilRight && isPaperColumn(bitmap, room.right + step - 1, room, paper)) {
                room.right += step
                growing = true
            }
            if (room.top - step >= floorTop && isPaperRow(bitmap, room.top - step, room, paper)) {
                room.top -= step
                growing = true
            }
            if (room.bottom + step <= ceilBottom && isPaperRow(bitmap, room.bottom + step - 1, room, paper)) {
                room.bottom += step
                growing = true
            }
        }
        // Stay off the bubble's outline.
        room.inset(min(2, room.width() / 8), min(2, room.height() / 8))
        return room
    }

    private fun isPaperColumn(bitmap: Bitmap, x: Int, room: Rect, paper: Int): Boolean {
        val stepY = max(1, room.height() / 14)
        var total = 0
        var matching = 0
        for (y in room.top until room.bottom step stepY) {
            total++
            if (isNear(bitmap.getPixel(x, y), paper)) matching++
        }
        return total > 0 && matching >= total * PAPER_SHARE
    }

    private fun isPaperRow(bitmap: Bitmap, y: Int, room: Rect, paper: Int): Boolean {
        val stepX = max(1, room.width() / 14)
        var total = 0
        var matching = 0
        for (x in room.left until room.right step stepX) {
            total++
            if (isNear(bitmap.getPixel(x, y), paper)) matching++
        }
        return total > 0 && matching >= total * PAPER_SHARE
    }

    private fun isNear(pixel: Int, paper: Int): Boolean {
        return abs(Color.luminance(pixel) - Color.luminance(paper)) < 0.14f
    }

    private fun layoutFor(text: String, paint: TextPaint, size: Float, width: Int): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.96f)
            .setIncludePad(false)
            .build()
    }

    /** Letters are darker than the bubble they sit on, so paper is the bright end of what's around them. */
    private fun paperColor(bitmap: Bitmap, area: Rect): Int {
        val samples = ArrayList<Int>(96)
        val stepX = max(1, area.width() / 20)
        val stepY = max(1, area.height() / 20)
        for (x in area.left until area.right step stepX) {
            samples += bitmap.getPixel(x, area.top)
            samples += bitmap.getPixel(x, area.bottom - 1)
        }
        for (y in area.top until area.bottom step stepY) {
            samples += bitmap.getPixel(area.left, y)
            samples += bitmap.getPixel(area.right - 1, y)
        }
        val sorted = samples.sortedBy(Color::luminance)
        val median = sorted[sorted.size / 2]
        return if (Color.luminance(median) < 0.3f) median else sorted[(sorted.size * 0.85f).toInt()]
    }

    private class TextArea(val box: Rect, val text: String, val lineHeight: Float)

    class PageLine(val text: String, val centerX: Float, val centerY: Float)

    class AlreadyItalianException : Exception()

    private companion object {
        const val MIN_TEXT_SIZE = 11f
        const val PAPER_SHARE = 0.86f
        const val READING_ROWS = 6
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
