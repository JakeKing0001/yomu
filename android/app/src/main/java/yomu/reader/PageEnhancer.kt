package yomu.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Doubles low-resolution scans and firms up strong line art, leaving flat tones and fine screentone alone. */
@Inject
@SingleIn(AppScope::class)
class PageEnhancer {

    suspend fun enhance(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val scale = if (max(bitmap.width, bitmap.height) < UPSCALE_BELOW) 2 else 1
        val source = if (scale == 1) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap.scale(bitmap.width * scale, bitmap.height * scale)
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = FloatArray(pixels.size) { index ->
            val pixel = pixels[index]
            Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f
        }
        val output = IntArray(pixels.size)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerWorker = max(1, height / workers)
        coroutineScope {
            (0 until height step rowsPerWorker).map { start ->
                async { sharpenRows(pixels, luma, output, width, height, start, min(height, start + rowsPerWorker)) }
            }.awaitAll()
        }
        Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888).also {
            if (source !== bitmap) source.recycle()
        }
    }

    private fun sharpenRows(
        pixels: IntArray,
        luma: FloatArray,
        output: IntArray,
        width: Int,
        height: Int,
        startRow: Int,
        endRow: Int,
    ) {
        for (y in startRow until endRow) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < height - 1) y + 1 else y
            for (x in 0 until width) {
                val left = if (x > 0) x - 1 else x
                val right = if (x < width - 1) x + 1 else x
                val index = y * width + x
                val center = luma[index]
                val north = luma[up * width + x]
                val south = luma[down * width + x]
                val west = luma[y * width + left]
                val east = luma[y * width + right]

                val gradient = abs(east - west) + abs(south - north)
                val edge = ((gradient - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
                val detail = center - (north + south + west + east) / 4f
                val lifted = detail * SHARPEN_AMOUNT * edge

                val pixel = pixels[index]
                output[index] = Color.argb(
                    Color.alpha(pixel),
                    (Color.red(pixel) + lifted).roundToInt().coerceIn(0, 255),
                    (Color.green(pixel) + lifted).roundToInt().coerceIn(0, 255),
                    (Color.blue(pixel) + lifted).roundToInt().coerceIn(0, 255),
                )
            }
        }
    }

    private companion object {
        const val UPSCALE_BELOW = 1500
        const val EDGE_LOW = 46f
        const val EDGE_HIGH = 150f
        const val SHARPEN_AMOUNT = 1.7f
    }
}
