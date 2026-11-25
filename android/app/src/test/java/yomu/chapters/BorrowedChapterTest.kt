package yomu.chapters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BorrowedChapterTest {

    @Test
    fun `a borrowed address keeps its source and its own address`() {
        val url = BorrowedChapter.url(4242L, "/manga/x/chapter-5?style=list")

        assertEquals(4242L to "/manga/x/chapter-5?style=list", BorrowedChapter.parse(url))
        assertNull(BorrowedChapter.parse("/manga/x/chapter-5"))
        assertFalse(BorrowedChapter.isBorrowed("https://site/chapter:5"))
    }

    @Test
    fun `fills gaps and newer chapters without doubling numbers already there`() {
        val have = listOf(1.0, 2.0, 4.0, 5.0)
        val offered = listOf(6.0 to "six", 5.5 to "five and a half", 4.0 to "four", 3.0 to "three", 3.5 to "three bis")

        assertEquals(listOf(6.0, 3.0), BorrowedChapter.missing(have, offered).map { it.first })
    }

    @Test
    fun `a source that numbers each season from one is not used to fill gaps`() {
        val seasons = (1..20).map { it.toDouble() } + (1..20).map { it.toDouble() }

        assertFalse(BorrowedChapter.isNumberedLikeTheSeries(seasons))
        assertTrue(BorrowedChapter.isNumberedLikeTheSeries((1..40).map { it.toDouble() } + 12.5))
    }

    @Test
    fun `a series without chapters takes every chapter offered`() {
        assertEquals(3, BorrowedChapter.missing(emptyList(), listOf(1.0 to 1, 2.0 to 2, 3.0 to 3)).size)
    }
}
