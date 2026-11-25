package yomu.discover

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

data class TasteItem(
    val id: Long,
    val title: String,
    val genres: List<String>,
    val description: String? = null,
)

data class Recommendation(val item: TasteItem, val score: Double, val because: String?)

/** Ranks a catalogue against what the reader kept and rejected. Everything stays on the device. */
object TasteEngine {

    private const val DISLIKE_WEIGHT = 0.6
    private const val POPULARITY_WEIGHT = 0.06
    private const val MIN_SHARED_FOR_REASON = 0.34

    /** [candidates] are expected most popular first, which settles ties and a reader with no history. */
    fun rank(
        candidates: List<TasteItem>,
        liked: List<TasteItem>,
        disliked: List<TasteItem> = emptyList(),
    ): List<Recommendation> {
        val excluded = (liked + disliked).mapTo(HashSet()) { it.id }
        val pool = candidates.filter { it.id !in excluded }
        if (pool.isEmpty()) return emptyList()

        val idf = inverseFrequency(pool + liked + disliked)
        val profile = HashMap<String, Double>()
        liked.forEach { item -> item.tags().forEach { profile.merge(it, idf[it] ?: 1.0, Double::plus) } }
        disliked.forEach { item ->
            item.tags().forEach { profile.merge(it, -(idf[it] ?: 1.0) * DISLIKE_WEIGHT, Double::plus) }
        }

        return pool.mapIndexed { index, item ->
            val popularity = 1.0 - index.toDouble() / pool.size
            val score = cosine(profile, item.tags().associateWith { idf[it] ?: 1.0 }) + popularity * POPULARITY_WEIGHT
            Recommendation(item, score, closest(item, liked)?.title)
        }.sortedByDescending { it.score }
    }

    private fun TasteItem.tags(): Set<String> {
        return genres.mapNotNullTo(HashSet()) { genre -> genre.trim().lowercase().takeIf(String::isNotEmpty) }
    }

    private fun inverseFrequency(items: List<TasteItem>): Map<String, Double> {
        val counts = HashMap<String, Int>()
        items.forEach { item -> item.tags().forEach { counts.merge(it, 1, Int::plus) } }
        return counts.mapValues { (_, count) -> ln((1.0 + items.size) / (1.0 + count)) + 1.0 }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = b.entries.sumOf { (tag, weight) -> (a[tag] ?: 0.0) * weight }
        val norm = sqrt(a.values.sumOf { it * it }) * sqrt(b.values.sumOf { it * it })
        return if (norm == 0.0) 0.0 else dot / norm
    }

    private fun closest(item: TasteItem, liked: List<TasteItem>): TasteItem? {
        val tags = item.tags()
        if (tags.isEmpty()) return null
        return liked
            .map { other ->
                val otherTags = other.tags()
                other to otherTags.intersect(tags).size.toDouble() / max(1, otherTags.union(tags).size)
            }
            .filter { (_, shared) -> shared >= MIN_SHARED_FOR_REASON }
            .maxByOrNull { (_, shared) -> shared }
            ?.first
    }
}
