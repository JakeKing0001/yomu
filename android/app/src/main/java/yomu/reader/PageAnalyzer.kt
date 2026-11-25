package yomu.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.scale
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

object PageAnalyzer {

    private const val ANALYSIS_WIDTH = 320
    private const val HUE_BUCKETS = 24
    private const val MIN_COLOR_SHARE = 0.08f

    /** Decodes the page once it's loaded, sampled down so its longest side is near [maxSide]. */
    suspend fun decode(page: ReaderPage, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val status = page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
        if (status != Page.State.Ready) return@withContext null
        val stream = page.stream ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        stream().use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Share of the page covered by ink, a stand-in for how long it takes to read. */
    fun inkDensity(bitmap: Bitmap): Float {
        val (pixels, _, _) = samplePixels(bitmap)
        val background = pixels.map(::luma).sorted()[pixels.size / 2]
        return pixels.count { kotlin.math.abs(luma(it) - background) > 48 } / pixels.size.toFloat()
    }

    fun panels(bitmap: Bitmap, rightToLeft: Boolean) = samplePixels(bitmap).let { (pixels, width, height) ->
        PanelDetector.detect(IntArray(pixels.size) { luma(pixels[it]) }, width, height, rightToLeft)
    }

    /** The page's most present vivid colour, or null for a black and white page. */
    fun ambientColor(bitmap: Bitmap): Int? {
        val (pixels, _, _) = samplePixels(bitmap)
        val weight = FloatArray(HUE_BUCKETS)
        val red = FloatArray(HUE_BUCKETS)
        val green = FloatArray(HUE_BUCKETS)
        val blue = FloatArray(HUE_BUCKETS)
        val hsv = FloatArray(3)
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.18f) continue
            val bucket = (hsv[0] / 360f * HUE_BUCKETS).toInt().coerceAtMost(HUE_BUCKETS - 1)
            val amount = hsv[1] * hsv[2]
            weight[bucket] += amount
            red[bucket] += Color.red(pixel) * amount
            green[bucket] += Color.green(pixel) * amount
            blue[bucket] += Color.blue(pixel) * amount
        }
        val best = weight.indices.maxBy { weight[it] }
        if (weight[best] < pixels.size * MIN_COLOR_SHARE * 0.5f) return null
        return Color.rgb(
            (red[best] / weight[best]).roundToInt(),
            (green[best] / weight[best]).roundToInt(),
            (blue[best] / weight[best]).roundToInt(),
        )
    }

    private fun samplePixels(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        val width = ANALYSIS_WIDTH.coerceAtMost(bitmap.width)
        val height = max(1, (bitmap.height * (width / bitmap.width.toFloat())).roundToInt())
        val small = if (width == bitmap.width) bitmap else bitmap.scale(width, height)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== bitmap) small.recycle()
        return Triple(pixels, width, height)
    }

    private fun luma(pixel: Int): Int {
        return (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
    }
}
