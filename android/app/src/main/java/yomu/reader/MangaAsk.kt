package yomu.reader

data class AskLine(val chapter: Double, val text: String)

data class MangaAnswer(val text: String, val quotes: List<AskLine>)

/** Picks the lines that can answer a question and accepts only answers those lines back up. */
object MangaAsk {

    private val WORD = Regex("""\p{L}+""")
    private val NUMBER = Regex("""\d+""")
    private val UNKNOWN = Regex("""\bNON\s+LO\s+SO\b""", RegexOption.IGNORE_CASE)
    private val ANSWER = Regex("""RISPOSTA\s*:\s*(.+?)(?=\n\s*RIGHE\s*:|\z)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val SOURCES = Regex("""RIGHE\s*:\s*(.+)""")
    private val QUESTION_WORDS = setOf(
        "chi", "che", "cosa", "come", "dove", "quando", "perche", "quale", "quali", "quanto", "quanti", "sono",
        "era", "erano", "stato", "stata", "fatto", "fatta", "della", "delle", "dello", "degli", "alla", "alle",
        "allo", "agli", "nella", "nelle", "nello", "con", "per", "tra", "fra", "una", "uno", "gli", "dei", "del",
        "dal", "dalla", "sul", "sulla", "non", "piu", "anche", "hanno", "successo", "succede",
    )

    private const val MAX_LINES = 70
    private const val MAX_QUOTES = 3
    private const val STEM_LENGTH = 5

    fun relevantLines(question: String, chapters: List<MemoryChapter>): List<AskLine> {
        val lines = chapters.flatMap { chapter ->
            RecapText.repairChapter(chapter.pages.flatMap(RecapText::cleanPage))
                .distinctBy(String::lowercase)
                .map { AskLine(chapter.number, it) }
        }
        val wanted = keywords(question)
        val hits = lines.indices
            .map { index -> index to keywords(lines[index].text).count(wanted::contains) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
            .take(MAX_LINES / 3)
            .map { it.first }

        val picked = sortedSetOf<Int>()
        // The line before or after a mention often says who is speaking or what happened next.
        hits.forEach { hit -> (hit - 1..hit + 1).filterTo(picked) { it in lines.indices } }
        var next = lines.lastIndex
        while (picked.size < MAX_LINES && next >= 0) picked += next--
        return picked.map(lines::get)
    }

    fun parse(answer: String, lines: List<AskLine>): MangaAnswer? {
        val clean = answer.replace("**", "").trim()
        if (UNKNOWN.containsMatchIn(clean)) return null
        val text = ANSWER.find(clean)?.groupValues?.get(1)?.trim().orEmpty()
        val quotes = SOURCES.find(clean)?.groupValues?.get(1).orEmpty()
            .let { NUMBER.findAll(it) }
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..lines.size }
            .distinct()
            .map { lines[it - 1] }
            .toList()
        if (text.isEmpty() || quotes.isEmpty()) return null
        if (!RecapText.isGrounded(text, quotes.map(AskLine::text), lines.map(AskLine::text))) return null
        return MangaAnswer(text, quotes.take(MAX_QUOTES))
    }

    private fun keywords(text: String): Set<String> {
        return WORD.findAll(text)
            .map { RecapText.plainWord(it.value) }
            .filter { it.length >= 3 && it !in QUESTION_WORDS }
            .mapTo(HashSet()) { it.take(STEM_LENGTH) }
    }
}
