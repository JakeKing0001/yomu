package yomu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiTilesTest {

    @Test
    fun `every pixel belongs to exactly one tile and stays inside its window`() {
        val width = 731
        val height = 1012
        val covered = IntArray(width * height)

        AiTiles.plan(width, height, size = 128, overlap = 12).forEach { tile ->
            assertTrue(tile.left >= tile.x && tile.right <= tile.x + 128)
            assertTrue(tile.top >= tile.y && tile.bottom <= tile.y + 128)
            for (y in tile.top until tile.bottom) {
                for (x in tile.left until tile.right) covered[y * width + x]++
            }
        }

        assertTrue(covered.all { it == 1 })
    }

    @Test
    fun `a page smaller than the window is a single tile`() {
        assertEquals(listOf(AiTile(0, 0, 0, 0, 90, 60)), AiTiles.plan(90, 60, size = 128, overlap = 12))
    }
}
