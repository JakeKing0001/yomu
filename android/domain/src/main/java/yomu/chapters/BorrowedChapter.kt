package yomu.chapters

import kotlin.math.floor

/**
 * A chapter a series' own source lacks, read from another source. Its address carries that source, so
 * the reader knows where to load it from and a refresh of the series doesn't delete it.
 */
object BorrowedChapter {

    private const val PREFIX = "yomu-alt:"
    private const val MAX_REPEATED_SHARE = 10

    fun url(sourceId: Long, url: String) = "$PREFIX$sourceId:$url"

    fun isBorrowed(url: String) = url.startsWith(PREFIX)

    /** The source and the address there, or null for an ordinary chapter. */
    fun parse(url: String): Pair<Long, String>? {
        if (!isBorrowed(url)) return null
        val rest = url.removePrefix(PREFIX)
        val sourceId = rest.substringBefore(':').toLongOrNull() ?: return null
        return sourceId to rest.substringAfter(':')
    }

    /**
     * A source that starts its numbering again, as some do for each season, would fill gaps with the wrong
     * chapters: its list repeats whole numbers, which a list numbered from the first chapter never does.
     */
    fun isNumberedLikeTheSeries(offered: List<Double>): Boolean {
        val wholes = offered.filter { it >= 0 }.map { floor(it) }
        if (wholes.isEmpty()) return false
        return wholes.size - wholes.toSet().size <= wholes.size / MAX_REPEATED_SHARE
    }

    /**
     * The chapters worth borrowing: those whose whole number the series doesn't have yet, one per number.
     * Comparing whole numbers keeps a 12.5 from another source from doubling a 12 already there.
     */
    fun <T> missing(have: Collection<Double>, offered: List<Pair<Double, T>>): List<Pair<Double, T>> {
        val covered = have.filter { it >= 0 }.mapTo(HashSet()) { floor(it) }
        return offered.filter { (number, _) -> number >= 0 && floor(number) !in covered }
            .distinctBy { (number, _) -> floor(number) }
    }
}
