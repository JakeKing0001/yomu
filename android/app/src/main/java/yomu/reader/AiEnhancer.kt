package yomu.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** One model window: where it starts, and the part of the page its result is used for. */
internal data class AiTile(val x: Int, val y: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

internal object AiTiles {

    /** Windows overlap and each keeps only its centre, because a model's output is worst at its edges. */
    fun plan(width: Int, height: Int, size: Int, overlap: Int): List<AiTile> {
        val columns = axis(width, size, overlap)
        val rows = axis(height, size, overlap)
        return rows.flatMap { (y, top, bottom) ->
            columns.map { (x, left, right) -> AiTile(x, y, left, top, right, bottom) }
        }
    }

    private fun axis(length: Int, size: Int, overlap: Int): List<Triple<Int, Int, Int>> {
        if (length <= size) return listOf(Triple(0, 0, length))
        val step = size - overlap * 2
        val starts = generateSequence(0) { it + step }.takeWhile { it < length - size }.toList() + (length - size)
        return starts.mapIndexed { index, start ->
            val from = if (index == 0) 0 else (starts[index - 1] + size + start) / 2
            val to = if (index == starts.lastIndex) length else (start + size + starts[index + 1]) / 2
            Triple(start, from, to)
        }
    }
}

/**
 * Rebuilds low-resolution pages with Real-ESRGAN General x4v3, on the device.
 * The model upscales 4×; the result is averaged down to 2× to keep a page within memory.
 */
@Inject
@SingleIn(AppScope::class)
class AiEnhancer(private val context: Context) {

    private val mutex = Mutex()
    private var interpreter: Interpreter? = null
    private val folder get() = File(context.cacheDir, "yomu_ai").apply { mkdirs() }

    /** Pages above this are already sharp enough, and would take minutes to process. */
    fun canEnhance(bitmap: Bitmap): Boolean = max(bitmap.width, bitmap.height) <= MAX_INPUT_SIDE

    fun isCached(key: String): Boolean = File(folder, "$key.jpg").exists()

    suspend fun enhance(source: Bitmap, key: String, onProgress: (Float) -> Unit): Bitmap =
        withContext(Dispatchers.Default) {
            val cached = File(folder, "$key.jpg")
            if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return@withContext it }

            val result = mutex.withLock {
                val model = interpreter ?: createInterpreter().also { interpreter = it }
                val width = source.width
                val height = source.height
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, 0, 0, width, height)
                val output = Bitmap.createBitmap(width * OUTPUT_SCALE, height * OUTPUT_SCALE, Bitmap.Config.ARGB_8888)
                val input = ByteBuffer.allocateDirect(
                    TILE * TILE * CHANNELS * FLOAT_BYTES,
                ).order(ByteOrder.nativeOrder())
                val raw = ByteBuffer.allocateDirect(TILE_OUT * TILE_OUT * CHANNELS * FLOAT_BYTES)
                    .order(ByteOrder.nativeOrder())
                val tiles = AiTiles.plan(width, height, TILE, OVERLAP)
                tiles.forEachIndexed { index, tile ->
                    ensureActive()
                    fillInput(input, pixels, width, height, tile)
                    raw.rewind()
                    model.run(input, raw)
                    writeTile(raw, output, tile)
                    onProgress((index + 1) / tiles.size.toFloat())
                }
                output
            }
            ensureActive()
            // Written aside first, so an app killed mid-write can't leave a broken page in the cache.
            val partial = File(folder, "$key.tmp")
            partial.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            partial.renameTo(cached)
            trimCache()
            result
        }

    private fun fillInput(input: ByteBuffer, pixels: IntArray, width: Int, height: Int, tile: AiTile) {
        input.rewind()
        for (row in 0 until TILE) {
            val y = (tile.y + row).coerceAtMost(height - 1)
            for (column in 0 until TILE) {
                val pixel = pixels[y * width + (tile.x + column).coerceAtMost(width - 1)]
                input.putFloat(Color.red(pixel) / 255f)
                input.putFloat(Color.green(pixel) / 255f)
                input.putFloat(Color.blue(pixel) / 255f)
            }
        }
        input.rewind()
    }

    private fun writeTile(raw: ByteBuffer, output: Bitmap, tile: AiTile) {
        val values = raw.apply { rewind() }.asFloatBuffer()
        val outLeft = tile.left * OUTPUT_SCALE
        val outTop = tile.top * OUTPUT_SCALE
        val outWidth = (tile.right - tile.left) * OUTPUT_SCALE
        val outHeight = (tile.bottom - tile.top) * OUTPUT_SCALE
        val colors = IntArray(outWidth * outHeight)
        for (row in 0 until outHeight) {
            val sourceY = (outTop + row - tile.y * OUTPUT_SCALE) * DOWNSAMPLE
            for (column in 0 until outWidth) {
                val sourceX = (outLeft + column - tile.x * OUTPUT_SCALE) * DOWNSAMPLE
                var red = 0f
                var green = 0f
                var blue = 0f
                for (dy in 0 until DOWNSAMPLE) {
                    for (dx in 0 until DOWNSAMPLE) {
                        val offset = ((sourceY + dy) * TILE_OUT + sourceX + dx) * CHANNELS
                        red += values[offset]
                        green += values[offset + 1]
                        blue += values[offset + 2]
                    }
                }
                colors[row * outWidth + column] = Color.rgb(channel(red), channel(green), channel(blue))
            }
        }
        output.setPixels(colors, 0, outWidth, outLeft, outTop, outWidth, outHeight)
    }

    private fun channel(sum: Float) = (sum / (DOWNSAMPLE * DOWNSAMPLE) * 255f).roundToInt().coerceIn(0, 255)

    private fun createInterpreter(): Interpreter {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val compatibility = CompatibilityList()
        if (compatibility.isDelegateSupportedOnThisDevice) {
            try {
                val options = Interpreter.Options().addDelegate(GpuDelegate(compatibility.bestOptionsForThisDevice))
                return Interpreter(model, options)
            } catch (e: Exception) {
                // Some drivers report support and then reject the model's operators.
                logcat(LogPriority.WARN, e) { "GPU unavailable for the page model, using the CPU" }
            }
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS)
        return Interpreter(model, Interpreter.Options().setNumThreads(threads))
    }

    private fun trimCache() {
        folder.listFiles()
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_CACHED_FILES)
            ?.forEach(File::delete)
    }

    private companion object {
        const val MODEL = "yomu/real_esrgan_general_x4v3.tflite"
        const val MAX_INPUT_SIDE = 1300
        const val TILE = 128
        const val TILE_OUT = 512
        const val OVERLAP = 12
        const val OUTPUT_SCALE = 2
        const val DOWNSAMPLE = 2
        const val CHANNELS = 3
        const val FLOAT_BYTES = 4
        const val JPEG_QUALITY = 93
        const val MAX_THREADS = 4
        const val MAX_CACHED_FILES = 120
    }
}
