package yomu.reader

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** Repairs text read from manga pages and checks what the model writes against it. */
object RecapText {

    private val SEPARATOR = Regex("""\s+/\s+|\n""")
    private val SYLLABLE_BREAK = Regex("""(?<=\p{L})[-–]\s+(?=\p{L})""")
    private val ZERO_IN_WORD = Regex("""(?<=\p{L})0(?=\p{L}|\b)""")
    private val ONE_IN_WORD = Regex("""(?<=\p{L})1(?=\p{L})""")
    private val SPACES = Regex("""\s+""")

    // Hand-lettered "?" and "d" come back from OCR as "p"; Italian words almost never end in "p".
    private val QUESTION_AS_P = Regex("""(?<=\p{L}{3}[aeiouAEIOU])[pP](?=[.!]*$)""")
    private val DOTS_AS_P = Regex("""\.+[pP]$""")
    private val D_AS_P = Regex("""\b[pP](?=(?:ell['’]|el|ei|ella|ello|egli)\b)""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""\p{L}+""")
    private val REPEATED_LETTER = Regex("""(.)\1{2,}""")
    private val VOWEL = Regex("""[aeiouàèéìòù]""")
    private val MARKS = Regex("""\p{M}+""")
    private val CREDITS = Regex(
        """\b(scan|scans|scanlation|www|http|https|credits?|traduzione|traduttore|typesetter|cleaner|mangaworld)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME = Regex("""\p{Lu}[\p{L}'’]+(?:\s+(?:\p{Lu}\.|\p{Lu}[\p{L}'’]+))*""")
    private val CHAPTER_HEADER = Regex("""^\W*CAPITOLO\s+(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("""^\s*[-•*]\s*(.+)$""")
    private val NUMBER = Regex("""\d+""")
    private val GARBLED = Regex("""\b(?=\w*\d)(?=\w*\p{L})\w+\b""")
    private val SPOKEN = Regex(
        """\b(io|tu|noi|voi|ho|hai|abbiamo|avete|sei|siamo|siete|voglio|vuoi|vogliamo|volete|""" +
            """mio|mia|tuo|tua|nostro|nostra|vostro|vostra)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val INTERJECTION = Regex("""^(ehi|eh|oh|ah|beh|ahah|uao|hey)\b""", RegexOption.IGNORE_CASE)

    private val ARTICLES = setOf("il", "lo", "la", "le", "gli", "un", "uno", "una", "i", "l")
    private val COMMON_WORDS = setOf(
        "alla", "alle", "allo", "agli", "anche", "ancora", "che", "chi", "come", "con", "cosa", "dalla", "dalle",
        "dallo", "degli", "della", "delle", "dello", "dopo", "essere", "fare", "hanno", "loro", "mentre", "molto",
        "nella", "nelle", "nello", "non", "nostro", "pero", "perche", "piu", "poi", "quando", "quella", "quelle",
        "quello", "questa", "queste", "questo", "sono", "sulla", "sulle", "sullo", "suoi", "tutti", "tutto", "una",
        "uno", "viene", "vengono", "vuole", "aver", "avere", "stato", "stata", "capitolo",
    )

    private val NOT_NAMES = COMMON_WORDS + setOf(
        "ammiraglio", "amico", "amici", "bambino", "bambina", "battaglia", "capitano", "capo", "ciurma", "citta",
        "dottore", "equipaggio", "flotta", "giovane", "grande", "isola", "madre", "mare", "marina", "marinai",
        "mondo", "nave", "navi", "nemico", "oceano", "padre", "padrona", "piccolo", "pirata", "pirati", "porto",
        "ragazza", "ragazzo", "sindaco", "signore", "signor", "uomo", "villaggio", "capitolo",
    )

    private const val MIN_LETTERS = 4
    private const val MIN_LETTER_SHARE = 0.6f
    private const val UPPERCASE_SHARE = 0.6f
    private const val MIN_SINGLE_WORD = 5
    private const val STEM_LENGTH = 5
    private const val MIN_CONTENT_WORD = 4
    private const val MIN_SUPPORT = 0.5f
    private const val MIN_ANSWER_SUPPORT = 0.34f
    private const val MAX_CHARACTERS = 8
    private const val MIN_NAME_LENGTH = 3
    private const val MIN_REPEATS = 3
    private const val MIN_QUOTED_WORDS = 4
    private const val NATURAL_ENDINGS = "aeiouynlrs"

    /** Rejoins hyphenated syllables, repairs common misreads and drops sound effects and credits. */
    fun cleanPage(text: String): List<String> {
        val seen = HashSet<String>()
        return text.split(SEPARATOR).mapNotNull { raw ->
            val line = repair(raw)
            if (!isDialogue(line) || !seen.add(line.lowercase())) return@mapNotNull null
            val letters = line.count(Char::isLetter)
            val uppercase = line.count(Char::isUpperCase)
            if (uppercase > letters * UPPERCASE_SHARE) {
                line.lowercase().replaceFirstChar(Char::uppercase)
            } else {
                line
            }
        }
    }

    private fun repair(raw: String): String {
        return raw.trim()
            .replace('|', 'I')
            .replace('[', 'I')
            .replace(']', 'I')
            .replace(SYLLABLE_BREAK, "")
            .replace(ZERO_IN_WORD, "O")
            .replace(ONE_IN_WORD, "I")
            .replace(QUESTION_AS_P, "?")
            .replace(DOTS_AS_P, "...?")
            .replace(D_AS_P) { if (it.value == "P") "D" else "d" }
            .replace(SPACES, " ")
            .trim(' ', ',', '-', '/')
            .trimStart('.')
            .trimEnd('.')
    }

    private fun isDialogue(line: String): Boolean {
        val letters = line.count(Char::isLetter)
        if (letters < MIN_LETTERS || letters < line.length * MIN_LETTER_SHARE) return false
        if (CREDITS.containsMatchIn(line) || GARBLED.containsMatchIn(line)) return false
        val words = WORD.findAll(line).map { it.value.lowercase() }.toList()
        if (words.size == 1 && words.first().length < MIN_SINGLE_WORD) return false
        if (words.size <= 2 && words.any(REPEATED_LETTER::containsMatchIn)) return false
        return words.any(VOWEL::containsMatchIn)
    }

    /**
     * True when most of the sentence's words come from [evidence] and it names nothing that isn't there.
     * Names are matched loosely because the evidence itself comes from OCR.
     */
    fun isSupported(sentence: String, evidence: List<String>): Boolean {
        if (!isNarration(sentence)) return false
        val source = evidence.joinToString(" ")
        val mine = stems(sentence)
        if (mine.isEmpty()) return false
        val available = stems(source)
        if (mine.count(available::contains) < mine.size * MIN_SUPPORT) return false
        val vocabulary = WORD.findAll(source).mapTo(HashSet()) { normalize(it.value) }
        return WORD.findAll(sentence).drop(1).none { it.value.first().isUpperCase() && !isKnown(it.value, vocabulary) }
    }

    /** Like [isSupported] for a direct answer, which may be a question's echo or quote a character. */
    fun isGrounded(answer: String, quoted: List<String>, allLines: List<String>): Boolean {
        val mine = stems(answer)
        if (mine.isEmpty()) return false
        val available = stems(quoted.joinToString(" "))
        if (mine.count(available::contains) < mine.size * MIN_ANSWER_SUPPORT) return false
        val vocabulary = WORD.findAll(allLines.joinToString(" ")).mapTo(HashSet()) { normalize(it.value) }
        return WORD.findAll(answer).drop(1).none { it.value.first().isUpperCase() && !isKnown(it.value, vocabulary) }
    }

    fun plainWord(word: String): String = normalize(word)

    /** A copied line of dialogue tells the reader nothing about who said it or what happened. */
    private fun isNarration(sentence: String): Boolean {
        return '!' !in sentence &&
            '?' !in sentence &&
            !SPOKEN.containsMatchIn(sentence) &&
            !INTERJECTION.containsMatchIn(sentence) &&
            !GARBLED.containsMatchIn(sentence)
    }

    private fun stems(text: String): Set<String> {
        return WORD.findAll(text)
            .map { normalize(it.value) }
            .filter { it.length >= MIN_CONTENT_WORD && it !in COMMON_WORDS }
            .mapTo(HashSet()) { it.take(STEM_LENGTH) }
    }

    private fun normalize(word: String): String {
        return Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
    }

    private fun isKnown(word: String, vocabulary: Set<String>): Boolean {
        val target = normalize(word)
        if (target.length < 3 || target in vocabulary) return true
        val limit = if (target.length <= 6) 1 else 2
        return vocabulary.any { it.isNotEmpty() && it[0] == target[0] && isWithin(target, it, limit) }
    }

    private fun isWithin(a: String, b: String, limit: Int): Boolean {
        if (abs(a.length - b.length) > limit) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            if (current.min() > limit) return false
            previous = current
        }
        return previous[b.length] <= limit
    }

    /** Proper names in the checked sentences; a capital that only opens a sentence doesn't count alone. */
    fun characters(sentences: List<String>): List<String> {
        val insideSentence = sentences
            .flatMap { sentence -> NAME.findAll(sentence).filter { it.range.first > 0 }.map { it.value }.toList() }
            .flatMapTo(HashSet()) { it.split(' ') }
        val names = LinkedHashSet<String>()
        sentences.forEach { sentence ->
            NAME.findAll(sentence).forEach { match ->
                val parts = match.value.split(' ').dropWhile { it.lowercase() in ARTICLES }
                val isAlone = match.range.first == 0 && parts.size == 1 && parts.first() !in insideSentence
                if (parts.isNotEmpty() && !isAlone) names += parts.joinToString(" ")
            }
        }
        return names
            .filter { name ->
                names.none { other -> other != name && other.split(' ').containsAll(name.split(' ')) }
            }
            .take(MAX_CHARACTERS)
    }

    /** The model's chapter sections, with each sentence under the chapter number it declared. */
    fun parseChronicle(answer: String): List<Pair<Double?, List<String>>> {
        val sections = ArrayList<Pair<Double?, MutableList<String>>>()
        answer.lineSequence().forEach { raw ->
            val line = raw.replace("**", "").trim()
            val header = CHAPTER_HEADER.find(line)
            val bullet = BULLET.find(line)
            when {
                header != null -> sections += header.groupValues[1].replace(',', '.').toDoubleOrNull() to ArrayList()
                bullet != null -> {
                    if (sections.isEmpty()) sections += null to ArrayList<String>()
                    sections.last().second += bullet.groupValues[1].trim()
                }
            }
        }
        return sections.filter { it.second.isNotEmpty() }
    }

    fun pickedLines(answer: String, lines: List<String>, limit: Int): List<String> {
        val picked = NUMBER.findAll(answer)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..lines.size }
            .toSortedSet()
            .map { lines[it - 1] }
        if (picked.size >= limit / 3) return picked.take(limit)
        val step = max(1, lines.size / limit)
        return lines.filterIndexed { index, _ -> index % step == 0 }.takeLast(limit)
    }

    /** A word misread once is restored from the spelling the same chapter uses repeatedly, as "rogtk" to "roger". */
    fun repairChapter(lines: List<String>): List<String> {
        val counts = lines.flatMap { line -> WORD.findAll(line).map { normalize(it.value) }.toList() }
            .groupingBy { it }
            .eachCount()
        val frequent = counts.filter { (word, count) -> count >= MIN_REPEATS && word.length >= MIN_CONTENT_WORD }.keys
        return lines.map { line ->
            WORD.replace(line) { match ->
                val word = normalize(match.value)
                if (!looksMisread(word) || counts.getValue(word) > 1) return@replace match.value
                val spelling = frequent.firstOrNull { it[0] == word[0] && isWithin(word, it, 2) }
                    ?: return@replace match.value
                if (match.value.first().isUpperCase()) spelling.replaceFirstChar(Char::uppercase) else spelling
            }
        }
    }

    private fun looksMisread(word: String) = word.length >= MIN_CONTENT_WORD && word.last() !in NATURAL_ENDINGS

    /** Character names the model listed, kept only when the lines really contain them. */
    fun parseCharacters(answer: String, evidence: List<String>): List<String> {
        val vocabulary = evidence.flatMapTo(HashSet()) { line ->
            WORD.findAll(line).map { normalize(it.value) }.toList()
        }
        val listed = answer.lineSequence()
            .map { it.replace("**", "").trim() }
            .firstOrNull { it.startsWith("PERSONAGGI", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?: return emptyList()
        return listed.split(',', ';')
            .map { it.trim(' ', '.', '-', '"') }
            .filter { name ->
                val parts = WORD.findAll(name).map { it.value }.toList()
                parts.isNotEmpty() &&
                    name.first().isUpperCase() &&
                    name.length >= MIN_NAME_LENGTH &&
                    (parts.size > 1 || normalize(name) !in NOT_NAMES) &&
                    parts.all { isKnown(it, vocabulary) }
            }
            .distinct()
            .take(MAX_CHARACTERS)
    }

    /** The sentence numbers approved by the check; null when its answer can't be read. */
    fun approvedNumbers(answer: String, count: Int): Set<Int>? {
        val numbers = NUMBER.findAll(answer).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        return numbers.filterTo(HashSet()) { it in 1..count }
    }

    /** The closing lines worth quoting back, skipping bare reactions like "Wow!". */
    fun meaningfulLines(lines: List<String>, limit: Int): List<String> {
        return lines.filter { line -> WORD.findAll(line).count() >= MIN_QUOTED_WORDS }
            .takeLast(limit)
            .ifEmpty { lines.takeLast(limit) }
    }

    fun chapterLabel(number: Double): String = number.toString().removeSuffix(".0")
}
