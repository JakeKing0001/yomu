package yomu.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {

    private fun entry(url: String, vararg chapters: Pair<String, SyncChapter>) =
        SyncEntry(1L, url, url, chapters = mapOf(*chapters))

    @Test
    fun `a series new on either device reaches the other`() {
        val plan = SyncEngine.merge(listOf(entry("a")), listOf(entry("b")), emptySet())

        assertEquals(setOf("1|a", "1|b"), plan.merged.map(SyncEntry::key).toSet())
        assertEquals(listOf("1|b"), plan.addLocally.map(SyncEntry::key))
        assertTrue(plan.removeLocally.isEmpty())
    }

    @Test
    fun `a series removed on one device does not come back`() {
        val removedHere = SyncEngine.merge(emptyList(), listOf(entry("a")), setOf("1|a"))
        val removedThere = SyncEngine.merge(listOf(entry("a")), emptyList(), setOf("1|a"))

        assertTrue(removedHere.merged.isEmpty())
        assertTrue(removedHere.addLocally.isEmpty())
        assertEquals(setOf("1|a"), removedThere.removeLocally)
    }

    @Test
    fun `progress keeps the furthest point from both devices`() {
        val mine = entry("a", "c1" to SyncChapter(read = true), "c2" to SyncChapter(page = 4))
        val theirs = entry("a", "c2" to SyncChapter(page = 9), "c3" to SyncChapter(bookmark = true))

        val plan = SyncEngine.merge(listOf(mine), listOf(theirs), setOf("1|a"))

        val merged = plan.updateLocally.single().chapters
        assertTrue(merged.getValue("c1").read)
        assertEquals(9, merged.getValue("c2").page)
        assertTrue(merged.getValue("c3").bookmark)
    }
}
