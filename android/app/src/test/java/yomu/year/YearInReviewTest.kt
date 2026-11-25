package yomu.year

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class YearInReviewTest {

    private fun event(manga: Long, title: String, at: LocalDateTime, minutes: Long = 5) = ReadEvent(
        mangaId = manga,
        title = title,
        genres = listOf("Azione"),
        readAtMs = at.toInstant(ZoneOffset.UTC).toEpochMilli(),
        durationMs = minutes * 60_000,
    )

    @Test
    fun `counts only the requested year and finds the longest streak`() {
        val events = listOf(
            event(1, "A", LocalDateTime.of(2025, 12, 31, 23, 0)),
            event(1, "A", LocalDateTime.of(2026, 3, 1, 23, 0)),
            event(1, "A", LocalDateTime.of(2026, 3, 2, 23, 30)),
            event(2, "B", LocalDateTime.of(2026, 3, 3, 1, 0)),
            event(2, "B", LocalDateTime.of(2026, 6, 10, 15, 0)),
        )

        val summary = YearInReview.summarize(events, 2026, ZoneOffset.UTC)

        assertEquals(4, summary.chapters)
        assertEquals(20, summary.minutes)
        assertEquals(2, summary.series)
        assertEquals(3, summary.longestStreak)
        assertEquals(2, summary.busiestMonth)
        assertEquals(ReaderType.Night, summary.readerType)
    }
}
