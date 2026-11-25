package yomu.discover

import java.text.Normalizer
import kotlin.math.ln

/** Finds a series from a loose description of it, matching word roots across title, genres and plot. */
object MemorySearch {

    private const val K1 = 1.4
    private const val B = 0.72
    private const val TITLE_WEIGHT = 3
    private const val GENRE_WEIGHT = 2

    private val stopWords = setOf(
        "a", "ad", "al", "alla", "alle", "agli", "ai", "allo", "anche", "che", "chi", "con", "come", "cui", "da", "dal",
        "dalla", "dei", "del", "della", "delle", "dello", "di", "e", "ed", "era", "gli", "ha", "hanno", "i", "il", "in",
        "io", "la", "le", "li", "lo", "ma", "mi", "ne", "nel", "nella", "non", "o", "per", "piu", "poi", "quel",
        "quella", "quello", "questo", "sa", "se", "si", "sono", "su", "sul", "sulla", "ti", "tra", "fra", "un", "una",
        "uno", "manga", "storia", "parla", "ricordo", "the", "of", "and", "to", "who", "with", "that", "is", "an",
    )

    private val suffixes = listOf(
        "amento", "imento", "azione", "azioni", "mente", "arsi", "ersi", "irsi", "ando", "endo", "ante", "ente",
        "ato", "ata", "ati", "ate", "ito", "ita", "iti", "ite", "uto", "uta", "are", "ere", "ire", "ano", "ono",
        "ing", "ers", "er", "ed", "es", "a", "e", "i", "o", "s",
    )

    fun <T> search(query: String, items: List<T>, limit: Int = 30, fields: (T) -> SearchFields): List<T> {
        val terms = tokens(query).distinct()
        if (terms.isEmpty() || items.isEmpty()) return emptyList()

        val documents = items.map { item ->
            val text = fields(item)
            buildList {
                repeat(TITLE_WEIGHT) { addAll(tokens(text.title)) }
                repeat(GENRE_WEIGHT) { text.genres.forEach { addAll(tokens(it)) } }
                addAll(tokens(text.description.orEmpty()))
            }.groupingBy { it }.eachCount()
        }
        val lengths = documents.map { document -> document.values.sum() }
        val averageLength = lengths.average().coerceAtLeast(1.0)
        val documentFrequency = terms.associateWith { term -> documents.count { term in it } }

        return items.indices
            .map { index ->
                val score = terms.sumOf { term ->
                    val frequency = documents[index][term] ?: return@sumOf 0.0
                    val matches = documentFrequency.getValue(term)
                    val idf = ln(1.0 + (items.size - matches + 0.5) / (matches + 0.5))
                    idf * frequency * (K1 + 1) /
                        (frequency + K1 * (1 - B + B * lengths[index] / averageLength))
                }
                index to score
            }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (index, _) -> items[index] }
    }

    internal fun tokens(text: String): List<String> {
        return Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stopWords }
            .map(::stem)
    }

    private fun stem(word: String): String {
        val suffix = suffixes.firstOrNull { word.endsWith(it) && word.length - it.length >= 3 } ?: return word
        return word.removeSuffix(suffix)
    }
}

data class SearchFields(val title: String, val genres: List<String>, val description: String?)
