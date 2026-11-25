package yomu.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Reads series pages that no extension knows, by what almost every reading site has in common: links
 * named after chapter numbers, and a chapter page that is mostly a run of images from one folder.
 */
object WebScraper {

    data class Series(val url: String, val title: String, val cover: String?)

    data class ChapterLink(val url: String, val name: String, val number: Float)

    data class TelegramPost(val id: Long, val text: String, val photos: List<String>)

    private val CHAPTER_IN_TEXT = Regex(
        """(?:chapter|capitolo|cap|ch|chap|episodio|episode|ep)\.?\s*[-_#:]?\s*(\d+(?:[.,]\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private const val MIN_SLUG = 3
    private val SECTIONS = setOf(
        "comic", "comics", "manga", "manhua", "manhwa", "series", "serie", "read", "title", "webtoon", "webtoons",
        "fumetti", "leggi", "reader", "book", "books", "chapter", "chapters", "capitoli", "online",
    )
    private val CHAPTER_IN_PATH = Regex(
        """(?:chapter|capitolo|cap|ch|chap|episode|episodio|ep)[-_/]?(\d+(?:[.-]\d+)?)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_A_PAGE = Regex(
        """logo|avatar|icon|banner|/ads?/|emoji|gravatar|spinner|loading|placeholder|favicon|splash""",
        RegexOption.IGNORE_CASE,
    )
    private val IMAGE_IN_SCRIPT =
        Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:jpe?g|png|webp|avif)""", RegexOption.IGNORE_CASE)
    private val IMAGE_ATTRIBUTES = listOf("data-src", "data-lazy-src", "data-original", "data-url", "src")
    private val BACKGROUND = Regex("""background-image:\s*url\(['"]?([^'")]+)""")
    private val TITLE_NOISE = Regex(
        """\b(read|online|free|manga|manhwa|manhua|webtoon|comic|english|ita|italiano|raw|scans?|chapter\s*\d*|capitolo\s*\d*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_SEPARATOR = Regex("""\s+[|–—-]\s+|\s+::\s+""")

    private const val MIN_CHAPTERS = 2
    private const val MIN_PAGES = 3

    fun parse(html: String, url: String): Document = Jsoup.parse(html, url)

    /** Chapters linked from a series page on its own site, newest first. */
    fun chapters(document: Document): List<ChapterLink> {
        val page = document.location().toHttpUrlOrNull() ?: return emptyList()
        // Sidebars link the latest chapters of other series, so a chapter must belong to this one.
        val slug = seriesSlug(page.pathSegments)
        val byNumber = LinkedHashMap<Float, ChapterLink>()
        document.select("a[href]").forEach { link ->
            val url = link.absUrl("href").substringBefore('#')
            val parsed = url.toHttpUrlOrNull() ?: return@forEach
            if (parsed.host != page.host || url == document.location()) return@forEach
            if (slug != null && !parsed.encodedPath.contains(slug, ignoreCase = true)) return@forEach
            val label = link.text().trim()
            val match = CHAPTER_IN_PATH.find(parsed.encodedPath) ?: CHAPTER_IN_TEXT.find(label) ?: return@forEach
            val number = match.groupValues[1].replace(',', '.').replace('-', '.').toFloatOrNull() ?: return@forEach
            // Link texts carry dates and view counts, so the name is rebuilt from the number.
            byNumber.putIfAbsent(number, ChapterLink(url, "Capitolo ${formatNumber(number)}", number))
        }
        if (byNumber.size < MIN_CHAPTERS) return emptyList()
        return byNumber.values.sortedByDescending(ChapterLink::number)
    }

    /** The path segment naming the series: the last one that isn't a chapter, a language or a section. */
    private fun seriesSlug(segments: List<String>): String? {
        return segments.map { it.substringBeforeLast('.') }
            .lastOrNull { segment ->
                segment.length >= MIN_SLUG &&
                    segment.lowercase() !in SECTIONS &&
                    CHAPTER_IN_PATH.find("/$segment") == null &&
                    !segment.all(Char::isDigit)
            }
    }

    /** The chapter's images: the largest group of pictures served from one folder, in page order. */
    fun pages(document: Document): List<String> {
        val fromTags = document.select("img").mapNotNull(::imageOf)
        largestFolder(fromTags)?.let { return it }
        val fromScripts = document.select("script").flatMap { script ->
            IMAGE_IN_SCRIPT.findAll(script.data()).map { it.value.replace("\\/", "/") }.toList()
        }
        return largestFolder(fromScripts).orEmpty()
    }

    fun series(document: Document): Series {
        val raw = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: document.title()
        val cover = document.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf(String::isNotEmpty)
        return Series(document.location(), cleanTitle(raw), cover)
    }

    fun cleanTitle(raw: String): String {
        val first = raw.split(TITLE_SEPARATOR).firstOrNull { it.isNotBlank() } ?: raw
        return first.replace(TITLE_NOISE, " ").replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '|')
            .ifEmpty { raw.trim() }
    }

    /** Result links of DuckDuckGo's plain HTML page, which wraps each one in a redirect. */
    fun searchResults(html: String): List<String> {
        val document = Jsoup.parse(html, "https://duckduckgo.com/")
        return document.select("a.result__a, a.result-link").mapNotNull { link ->
            val href = link.absUrl("href")
            val target = href.toHttpUrlOrNull()?.queryParameter("uddg") ?: href
            target.takeIf { it.startsWith("http") }?.let { URLDecoder.decode(it, "UTF-8") }
        }.distinct()
    }

    /** The page titles DuckDuckGo shows for its results. */
    fun searchTitles(html: String): List<String> {
        return Jsoup.parse(html, "https://duckduckgo.com/").select("a.result__a, a.result-link")
            .map { it.text().trim() }
            .filter(String::isNotEmpty)
    }

    /** A chapter page's own number, from its address or else its title. */
    fun chapterNumberOf(url: String, title: String): Float? {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val match = CHAPTER_IN_PATH.find(path) ?: CHAPTER_IN_TEXT.find(title) ?: return null
        return match.groupValues[1].replace(',', '.').replace('-', '.').toFloatOrNull()
    }

    fun telegramChannel(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != "t.me" && parsed.host != "telegram.me") return null
        val segments = parsed.pathSegments.filter(String::isNotEmpty)
        val name = (if (segments.firstOrNull() == "s") segments.getOrNull(1) else segments.firstOrNull()) ?: return null
        return name.takeIf { it.matches(Regex("""[A-Za-z][A-Za-z0-9_]{3,}""")) && it !in TELEGRAM_RESERVED }
    }

    /** Posts of a public channel preview that carry pictures. */
    fun telegramPosts(document: Document): List<TelegramPost> {
        return document.select(".tgme_widget_message[data-post]").mapNotNull { post ->
            val id = post.attr("data-post").substringAfterLast('/').toLongOrNull() ?: return@mapNotNull null
            val photos = post.select(".tgme_widget_message_photo_wrap").mapNotNull { photo ->
                BACKGROUND.find(photo.attr("style"))?.groupValues?.get(1)
            }
            if (photos.isEmpty()) return@mapNotNull null
            TelegramPost(id, post.selectFirst(".tgme_widget_message_text")?.text().orEmpty(), photos)
        }
    }

    private fun imageOf(element: Element): String? {
        val url = IMAGE_ATTRIBUTES.asSequence()
            .map { element.absUrl(it).trim() }
            .firstOrNull { it.startsWith("http") }
            ?: element.attr("srcset").substringBefore(' ').takeIf { it.startsWith("http") }
            ?: return null
        return url.takeUnless { NOT_A_PAGE.containsMatchIn(it) }
    }

    private fun largestFolder(urls: List<String>): List<String>? {
        return urls.distinct()
            .groupBy { it.substringBefore('?').substringBeforeLast('/') }
            .values
            .maxByOrNull { it.size }
            ?.takeIf { it.size >= MIN_PAGES }
    }

    private fun formatNumber(number: Float) = number.toString().removeSuffix(".0")

    private val TELEGRAM_RESERVED = setOf("joinchat", "addstickers", "share", "proxy", "socks", "iv", "contact")
}
