package yomu.reader

import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelDetectorTest {

    private val width = 200
    private val height = 300

    private fun page(vararg panels: IntArray, background: Int = 255, ink: Int = 20): IntArray {
        val luma = IntArray(width * height) { background }
        panels.forEach { (left, top, right, bottom) ->
            for (y in top until bottom) for (x in left until right) luma[y * width + x] = ink
        }
        return luma
    }

    private val threePanels = arrayOf(
        intArrayOf(10, 10, 190, 140),
        intArrayOf(10, 155, 95, 290),
        intArrayOf(105, 155, 190, 290),
    )

    @Test
    fun `reads right to left within a row`() {
        val panels = PanelDetector.detect(page(*threePanels), width, height, rightToLeft = true)

        assertEquals(3, panels.size)
        assertTrue(panels[0].bottom < 0.5f)
        assertTrue(panels[1].left > 0.5f)
        assertTrue(panels[2].right < 0.5f)
    }

    @Test
    fun `reads left to right within a row`() {
        val panels = PanelDetector.detect(page(*threePanels), width, height, rightToLeft = false)

        assertEquals(3, panels.size)
        assertTrue(panels[1].right < 0.5f)
        assertTrue(panels[2].left > 0.5f)
    }

    @Test
    fun `works on black gutters`() {
        val luma = page(*threePanels, background = 0, ink = 230)

        assertEquals(3, PanelDetector.detect(luma, width, height, rightToLeft = true).size)
    }

    @Test
    fun `a page without gutters stays whole`() {
        val luma = page(intArrayOf(0, 0, width, height))

        assertEquals(listOf(Rect(0f, 0f, 1f, 1f)), PanelDetector.detect(luma, width, height, rightToLeft = true))
    }

    @Test
    fun `a caption overhanging one side doesn't hide the gutter`() {
        val luma = page(*threePanels, intArrayOf(10, 130, 45, 165))

        assertEquals(3, PanelDetector.detect(luma, width, height, rightToLeft = true).size)
    }

    @Test
    fun `an empty stretch between a panel's frames doesn't split it`() {
        val luma = page(
            intArrayOf(10, 10, 14, 290),
            intArrayOf(186, 10, 190, 290),
            intArrayOf(10, 10, 190, 14),
            intArrayOf(10, 286, 190, 290),
        )

        assertEquals(listOf(Rect(0f, 0f, 1f, 1f)), PanelDetector.detect(luma, width, height, rightToLeft = true))
    }

    @Test
    fun `a small caption joins a panel`() {
        val luma = page(*threePanels, intArrayOf(90, 144, 110, 151))

        assertEquals(3, PanelDetector.detect(luma, width, height, rightToLeft = true).size)
    }
}
