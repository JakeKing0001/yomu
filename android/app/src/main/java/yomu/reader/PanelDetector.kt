package yomu.reader

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Finds comic panels by recursively cutting a page along its empty gutters. */
object PanelDetector {

    private val fullPage = Rect(0f, 0f, 1f, 1f)

    /** Returns panels in reading order as fractions of the page; the whole page if none can be told apart. */
    fun detect(luma: IntArray, width: Int, height: Int, rightToLeft: Boolean): List<Rect> {
        if (width < 8 || height < 8) return listOf(fullPage)

        val background = borderMedian(luma, width, height)
        val ink = InkMap(luma, width, height, background)
        val found = ArrayList<Region>()
        ink.trim(Region(0, 0, width, height))?.let { cut(ink, it, cutRows = true, 0, rightToLeft, found) }

        val merged = mergeFragments(found, width * height)
        if (merged.size !in 2..MAX_PANELS) return listOf(fullPage)
        if (merged.any { it.area > width * height * FULL_PAGE_SHARE }) return listOf(fullPage)

        val marginX = width * PANEL_MARGIN
        val marginY = height * PANEL_MARGIN
        return merged.map {
            Rect(
                left = max(0f, (it.left - marginX) / width),
                top = max(0f, (it.top - marginY) / height),
                right = min(1f, (it.right + marginX) / width),
                bottom = min(1f, (it.bottom + marginY) / height),
            )
        }
    }

    private fun cut(ink: InkMap, region: Region, cutRows: Boolean, depth: Int, rtl: Boolean, out: MutableList<Region>) {
        if (depth >= MAX_DEPTH) {
            out += region
            return
        }
        var rows = cutRows
        var parts = ink.split(region, rows)
        if (parts.size < 2) {
            rows = !rows
            parts = ink.split(region, rows)
        }
        if (parts.size < 2) {
            out += region
            return
        }
        val ordered = if (!rows && rtl) parts.asReversed() else parts
        ordered.forEach { cut(ink, it, !rows, depth + 1, rtl, out) }
    }

    /** Captions and stray marks are too small to be panels, so they join the closest real one. */
    private fun mergeFragments(regions: List<Region>, pageArea: Int): List<Region> {
        val minArea = pageArea * MIN_PANEL_SHARE
        val panels = regions.filter { it.area >= minArea }.toMutableList()
        if (panels.isEmpty()) return emptyList()
        regions.filter { it.area < minArea }.forEach { fragment ->
            val index = panels.indices.minBy { panels[it].distanceTo(fragment) }
            panels[index] = panels[index].union(fragment)
        }
        return panels
    }

    private fun borderMedian(luma: IntArray, width: Int, height: Int): Int {
        val histogram = IntArray(256)
        for (x in 0 until width) {
            histogram[luma[x].coerceIn(0, 255)]++
            histogram[luma[(height - 1) * width + x].coerceIn(0, 255)]++
        }
        for (y in 0 until height) {
            histogram[luma[y * width].coerceIn(0, 255)]++
            histogram[luma[y * width + width - 1].coerceIn(0, 255)]++
        }
        val half = histogram.sum() / 2
        var seen = 0
        histogram.forEachIndexed { value, count ->
            seen += count
            if (seen >= half) return value
        }
        return 255
    }

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width * height

        fun union(other: Region) = Region(
            min(left, other.left),
            min(top, other.top),
            max(right, other.right),
            max(bottom, other.bottom),
        )

        fun distanceTo(other: Region): Float = hypot(
            ((left + right) - (other.left + other.right)) / 2f,
            ((top + bottom) - (other.top + other.bottom)) / 2f,
        )
    }

    /** Summed-area table of the pixels that differ from the page background. */
    private class InkMap(luma: IntArray, private val width: Int, private val height: Int, background: Int) {
        private val sums = IntArray((width + 1) * (height + 1))

        init {
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) {
                    if (abs(luma[y * width + x] - background) > INK_THRESHOLD) row++
                    sums[(y + 1) * (width + 1) + x + 1] = sums[y * (width + 1) + x + 1] + row
                }
            }
        }

        private fun count(left: Int, top: Int, right: Int, bottom: Int): Int {
            val stride = width + 1
            return sums[bottom * stride + right] - sums[top * stride + right] -
                sums[bottom * stride + left] + sums[top * stride + left]
        }

        private fun isEmptyLine(region: Region, rows: Boolean, position: Int): Boolean {
            return if (rows) {
                count(region.left, position, region.right, position + 1) <= region.width * GUTTER_TOLERANCE
            } else {
                count(position, region.top, position + 1, region.bottom) <= region.height * GUTTER_TOLERANCE
            }
        }

        /** A gutter may be crossed by a caption on one side, but never by the frames on both sides of a panel. */
        private fun isGutterLine(region: Region, rows: Boolean, position: Int): Boolean {
            if (isEmptyLine(region, rows, position)) return true
            val length = if (rows) region.width else region.height
            val start = if (rows) region.left else region.top
            val third = length / 3
            if (third == 0) return false
            val allowed = third * GUTTER_TOLERANCE
            val thirds = List(3) { index ->
                val from = start + third * index
                val to = if (index == 2) start + length else from + third
                val ink = if (rows) count(from, position, to, position + 1) else count(position, from, position + 1, to)
                ink <= allowed
            }
            return thirds[1] && (thirds[0] || thirds[2])
        }

        fun trim(region: Region): Region? {
            var (left, top, right, bottom) = region
            while (top < bottom && isEmptyLine(Region(left, top, right, bottom), true, top)) top++
            while (bottom > top && isEmptyLine(Region(left, top, right, bottom), true, bottom - 1)) bottom--
            while (left < right && isEmptyLine(Region(left, top, right, bottom), false, left)) left++
            while (right > left && isEmptyLine(Region(left, top, right, bottom), false, right - 1)) right--
            return Region(left, top, right, bottom).takeIf { it.width > 0 && it.height > 0 }
        }

        fun split(region: Region, rows: Boolean): List<Region> {
            val start = if (rows) region.top else region.left
            val end = if (rows) region.bottom else region.right
            val minGap = max(2, ((if (rows) height else width) * MIN_GUTTER_SHARE).toInt())
            val parts = ArrayList<Region>()
            var partStart = start
            var position = start
            while (position < end) {
                if (!isGutterLine(region, rows, position)) {
                    position++
                    continue
                }
                var gapEnd = position
                while (gapEnd < end && isGutterLine(region, rows, gapEnd)) gapEnd++
                if (gapEnd - position >= minGap && position > partStart) {
                    part(region, rows, partStart, position)?.let(parts::add)
                    partStart = gapEnd
                }
                position = gapEnd
            }
            if (partStart < end) part(region, rows, partStart, end)?.let(parts::add)
            return parts
        }

        private fun part(region: Region, rows: Boolean, start: Int, end: Int): Region? {
            return trim(
                if (rows) {
                    region.copy(top = start, bottom = end)
                } else {
                    region.copy(left = start, right = end)
                },
            )
        }
    }

    private const val INK_THRESHOLD = 42
    private const val GUTTER_TOLERANCE = 0.012f
    private const val MIN_GUTTER_SHARE = 0.007f
    private const val MIN_PANEL_SHARE = 0.03f
    private const val FULL_PAGE_SHARE = 0.86f
    private const val PANEL_MARGIN = 0.006f
    private const val MAX_PANELS = 16
    private const val MAX_DEPTH = 6
}
