package yomu.year

import java.time.Instant
import java.time.ZoneId

data class ReadEvent(
    val mangaId: Long,
    val title: String,
    val genres: List<String>,
    val readAtMs: Long,
    val durationMs: Long,
)

enum class ReaderType { Night, Morning, Afternoon, Evening }

data class YearSummary(
    val year: Int,
    val chapters: Int,
    val minutes: Long,
    val series: Int,
    val topSeries: List<Pair<String, Int>>,
    val topGenres: List<String>,
    val activeDays: Int,
    val longestStreak: Int,
    val months: List<Int>,
    val busiestMonth: Int?,
    val readerType: ReaderType?,
)

object YearInReview {

    private const val TOP_SERIES = 3
    private const val TOP_GENRES = 3
    private const val MS_PER_MINUTE = 60_000L

    // Sources list the publication format among the genres.
    private val FORMATS = setOf("manga", "manhwa", "manhua", "oneshot", "one shot", "webtoon")

    fun summarize(events: List<ReadEvent>, year: Int, zone: ZoneId): YearSummary {
        val inYear = events
            .map { it to Instant.ofEpochMilli(it.readAtMs).atZone(zone) }
            .filter { (_, time) -> time.year == year }
        val months = MutableList(12) { 0 }
        inYear.forEach { (_, time) -> months[time.monthValue - 1]++ }

        val days = inYear.map { (_, time) -> time.toLocalDate() }.toSortedSet()
        var longest = 0
        var current = 0
        var previous: java.time.LocalDate? = null
        days.forEach { day ->
            current = if (previous?.plusDays(1) == day) current + 1 else 1
            longest = maxOf(longest, current)
            previous = day
        }

        val readerType = inYear.groupingBy { (_, time) -> typeOf(time.hour) }.eachCount()
            .maxByOrNull { it.value }
            ?.key

        return YearSummary(
            year = year,
            chapters = inYear.size,
            minutes = inYear.sumOf { (event, _) -> event.durationMs.coerceAtLeast(0) } / MS_PER_MINUTE,
            series = inYear.distinctBy { (event, _) -> event.mangaId }.size,
            topSeries = inYear.groupBy { (event, _) -> event.mangaId }.values
                .sortedByDescending { it.size }
                .take(TOP_SERIES)
                .map { it.first().first.title to it.size },
            topGenres = inYear.flatMap { (event, _) -> event.genres }
                .filter { it.isNotBlank() && it.trim().lowercase() !in FORMATS }
                .groupingBy { it.trim() }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(TOP_GENRES)
                .map { it.key },
            activeDays = days.size,
            longestStreak = longest,
            months = months,
            busiestMonth = months.indices.maxByOrNull { months[it] }?.takeIf { months[it] > 0 },
            readerType = readerType,
        )
    }

    private fun typeOf(hour: Int): ReaderType = when (hour) {
        in 5..11 -> ReaderType.Morning
        in 12..17 -> ReaderType.Afternoon
        in 18..21 -> ReaderType.Evening
        else -> ReaderType.Night
    }
}
