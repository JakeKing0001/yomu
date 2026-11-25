package yomu.web

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import yomu.search.TitleMatch
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

/**
 * A built-in source that makes series out of the web: it looks up the chapters of a title on reading
 * sites no extension covers, keeps the sites where they really read, and joins what it finds there into
 * one series. Public Telegram channels that post chapters as pictures count too. Everything happens in
 * the background, with plain page requests.
 */
@Inject
@SingleIn(AppScope::class)
class WebSource : HttpSource() {

    override val name = "Web e Telegram"
    override val lang = "all"
    override val baseUrl = "https://duckduckgo.com"
    override val supportsLatest = false
    override val id: Long = ID

    private val searchGate = Mutex()
    private var lastSearchAt = 0L
    private var blockedUntil = 0L

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1 || query.isBlank()) return MangasPage(emptyList(), false)
        val titles = listOf(query)
        val urls = SEARCHES.flatMap { template -> webResults(template.replace("%s", query)) }
            .filter(::isReadingSite)
            .distinctBy { it.toHttpUrlOrNull()?.host }
            .take(MAX_SITES)
        val channels = webResults("site:t.me $query").mapNotNull(WebScraper::telegramChannel).distinct()
            .take(MAX_CHANNELS)

        val gate = Semaphore(PARALLEL)
        val (sites, posts) = coroutineScope {
            val sites = urls.map { url -> async { gate.withPermit { finding(url, titles) } } }
            val posts = channels.map { channel -> async { gate.withPermit { channelSeries(channel, query) } } }
            sites.awaitAll().filterNotNull() to posts.awaitAll().filterNotNull()
        }
        val joined = sites.takeIf { it.isNotEmpty() }?.let { found ->
            SManga.create().apply {
                url = WebSeries(query, found.map(Finding::url)).toString()
                title = query
                thumbnail_url = found.firstNotNullOfOrNull(Finding::cover)
            }
        }
        return MangasPage(listOfNotNull(joined) + posts, false)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        TelegramRef.parse(manga.url)?.let { telegram ->
            val posts = WebScraper.telegramPosts(document(telegram.searchUrl()))
            val list = posts.sortedByDescending { it.id }.mapIndexed { index, post ->
                SChapter.create().apply {
                    url = "https://t.me/${telegram.channel}/${post.id}"
                    name = post.text.take(MAX_NAME).ifBlank { "Post ${post.id}" }
                    chapter_number = (posts.size - index).toFloat()
                }
            }
            return SMangaUpdate(manga, if (fetchChapters) list else chapters)
        }
        val series = WebSeries.parse(manga.url) ?: WebSeries(manga.title, listOf(manga.url))
        manga.initialized = true
        return SMangaUpdate(manga, if (fetchChapters) assemble(series) else chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val images = if (chapter.url.toHttpUrlOrNull()?.host == "t.me") {
            WebScraper.telegramPosts(document("${chapter.url}?embed=1&mode=tme")).flatMap { it.photos }
        } else {
            WebScraper.pages(document(chapter.url))
        }
        return images.mapIndexed { index, image -> Page(index, chapter.url, image) }
    }

    override fun getMangaUrl(manga: SManga): String {
        return TelegramRef.parse(manga.url)?.let { "https://t.me/s/${it.channel}" }
            ?: WebSeries.parse(manga.url)?.sites?.firstOrNull()
            ?: manga.url
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    override fun imageRequest(page: Page): Request {
        // Image hosts often refuse pictures requested without the page that shows them.
        return GET(page.imageUrl!!, headersBuilder().set("Referer", page.url).build())
    }

    private class Finding(val url: String, val cover: String?)

    /** A page that really is about the title and leads to chapters that read here. */
    private suspend fun finding(url: String, titles: List<String>): Finding? = attempt {
        val document = document(url)
        val series = WebScraper.series(document)
        if (!TitleMatch.isStrong(TitleMatch.score(series.title, titles))) return@attempt null
        val isChapter =
            WebScraper.chapterNumberOf(url, document.title()) != null && WebScraper.pages(document).isNotEmpty()
        val links = WebScraper.chapters(document)
        // Sites that draw their pages with scripts list chapters that can't be read here.
        val reads = isChapter || (links.isNotEmpty() && WebScraper.pages(document(links.last().url)).isNotEmpty())
        if (!reads) null else Finding(url, series.cover)
    }

    /**
     * The whole series from the pages found: chapters listed on each site, the best stocked site first,
     * then a web search for each number still missing.
     */
    private suspend fun assemble(series: WebSeries): List<SChapter> {
        val byNumber = LinkedHashMap<Float, WebScraper.ChapterLink>()
        series.sites.mapNotNull { site -> attempt { chaptersFrom(site) } }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }
            .forEach { links -> links.forEach { byNumber.putIfAbsent(it.number, it) } }

        val highest = byNumber.keys.maxOrNull()?.toInt() ?: 0
        val covered = byNumber.keys.mapTo(HashSet()) { floor(it).toInt() }
        (1..highest).filter { it !in covered }.take(MAX_GAP_SEARCHES).forEach { number ->
            findChapter(series.title, number)?.let { byNumber[number.toFloat()] = it }
        }
        if (byNumber.isEmpty()) findChapter(series.title, 1)?.let { byNumber[1f] = it }

        return byNumber.values.sortedByDescending { it.number }.map { link ->
            SChapter.create().apply {
                url = link.url
                name = link.name
                chapter_number = link.number
                scanlator = link.url.toHttpUrlOrNull()?.host?.removePrefix("www.")
            }
        }
    }

    /** The chapters a page links to, plus the page itself when it is a chapter. */
    private suspend fun chaptersFrom(url: String): List<WebScraper.ChapterLink> {
        val document = document(url)
        val links = WebScraper.chapters(document).toMutableList()
        if (WebScraper.pages(document).isNotEmpty()) {
            WebScraper.chapterNumberOf(url, document.title())?.let { links += chapterLink(url, it) }
        } else if (links.isNotEmpty() && WebScraper.pages(document(links.last().url)).isEmpty()) {
            return emptyList()
        }
        return links.distinctBy { it.number }
    }

    private suspend fun findChapter(title: String, number: Int): WebScraper.ChapterLink? {
        val candidates = webResults("$title capitolo $number").filter(::isReadingSite).take(MAX_GAP_PAGES)
        return candidates.firstNotNullOfOrNull { url ->
            attempt {
                val document = document(url)
                val found = WebScraper.chapterNumberOf(url, document.title())
                val about = TitleMatch.isStrong(TitleMatch.score(WebScraper.series(document).title, listOf(title)))
                if (found != null && floor(found).toInt() == number && about &&
                    WebScraper.pages(document).isNotEmpty()
                ) {
                    chapterLink(url, found)
                } else {
                    null
                }
            }
        }
    }

    private fun chapterLink(url: String, number: Float) =
        WebScraper.ChapterLink(url, "Capitolo ${number.toString().removeSuffix(".0")}", number)

    private suspend fun channelSeries(channel: String, query: String): SManga? {
        return attempt {
            val ref = TelegramRef(channel, query)
            val posts = WebScraper.telegramPosts(document(ref.searchUrl()))
            if (posts.isEmpty()) return@attempt null
            SManga.create().apply {
                url = ref.toString()
                title = "$query · @$channel"
                thumbnail_url = posts.maxBy { it.id }.photos.first()
            }
        }
    }

    private fun isReadingSite(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return SKIPPED_HOSTS.none { host.endsWith(it) } && !ADULT_HOST.containsMatchIn(host)
    }

    /**
     * DuckDuckGo turns away bursts of searches, so they go one at a time with a pause between them,
     * and stop for a while once it answers with its robot check.
     */
    private suspend fun webResults(query: String): List<String> = searchGate.withLock {
        if (System.currentTimeMillis() < blockedUntil) return@withLock emptyList()
        val wait = lastSearchAt + SEARCH_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            var blocked = 0
            val found = ENGINES.firstNotNullOfOrNull { engine ->
                val url = engine.toHttpUrl().newBuilder().addQueryParameter("q", query).build()
                attempt {
                    client.newCall(GET(url, headers)).await().use { response ->
                        if (response.code in BLOCKED_CODES) blocked++
                        if (response.code != 200) null else WebScraper.searchResults(response.body.string())
                    }
                }?.takeIf { it.isNotEmpty() }
            }
            if (blocked == ENGINES.size) blockedUntil = System.currentTimeMillis() + BLOCKED_PAUSE_MS
            found.orEmpty()
        } finally {
            lastSearchAt = System.currentTimeMillis()
        }
    }

    private suspend fun document(url: String) = client.newCall(GET(url, headers)).await().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        WebScraper.parse(response.body.string(), response.request.url.toString())
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? {
        return try {
            withTimeoutOrNull(PAGE_TIMEOUT) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Web source skipped a page" }
            null
        }
    }

    /** A series put together from several sites: its title, and the pages where its chapters were found. */
    data class WebSeries(val title: String, val sites: List<String>) {
        override fun toString() = PREFIX + (listOf(URLEncoder.encode(title, "UTF-8")) + sites).joinToString("|")

        companion object {
            private const val PREFIX = "web:"

            fun parse(url: String): WebSeries? {
                if (!url.startsWith(PREFIX)) return null
                val parts = url.removePrefix(PREFIX).split('|')
                return WebSeries(URLDecoder.decode(parts.first(), "UTF-8"), parts.drop(1).filter(String::isNotEmpty))
            }
        }
    }

    /** A Telegram "series": the posts of one channel that match the searched title. */
    private data class TelegramRef(val channel: String, val query: String) {
        fun searchUrl() = "https://t.me/s/$channel".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
            .toString()

        override fun toString() = "$PREFIX$channel|$query"

        companion object {
            const val PREFIX = "tg:"

            fun parse(url: String): TelegramRef? {
                if (!url.startsWith(PREFIX)) return null
                val (channel, query) = url.removePrefix(PREFIX).split('|', limit = 2).takeIf { it.size == 2 }
                    ?: return null
                return TelegramRef(channel, query)
            }
        }
    }

    companion object {
        /** Fixed, so series found on the web keep working across installs and backups. */
        const val ID = 4_891_270_117_303_402_841L
        private const val MAX_SITES = 10
        private const val MAX_CHANNELS = 3
        private const val MAX_GAP_SEARCHES = 5
        private const val MAX_GAP_PAGES = 3
        private const val PARALLEL = 4
        private const val MAX_NAME = 80
        private const val SEARCH_GAP_MS = 2_500L
        private const val BLOCKED_PAUSE_MS = 10 * 60_000L
        private val BLOCKED_CODES = setOf(202, 403, 429)
        private val PAGE_TIMEOUT = 15.seconds
        private val ENGINES = listOf("https://html.duckduckgo.com/html/", "https://lite.duckduckgo.com/lite/")

        /** What a reader would type to find the first chapter, in Italian and in English. */
        private val SEARCHES = listOf("%s scan ita capitolo 1", "%s chapter 1", "%s manga")

        private val ADULT_HOST = Regex("""hentai|porn|xxx|rule34|sex|adult|manhwa18|18\+""", RegexOption.IGNORE_CASE)

        /** Sites about manga that don't host chapters, and catalogues Yomu already searches through extensions. */
        private val SKIPPED_HOSTS = listOf(
            "wikipedia.org", "myanimelist.net", "anilist.co", "mangaupdates.com", "fandom.com", "reddit.com",
            "youtube.com", "amazon.com", "amazon.it", "goodreads.com", "imdb.com", "twitter.com", "x.com",
            "facebook.com", "instagram.com", "tiktok.com", "novelupdates.com", "anime-planet.com", "t.me",
            "mangadex.org", "webtoons.com", "animeclick.it", "pinterest.com", "google.com", "bing.com",
            "duckduckgo.com", "wiktionary.org", "linkedin.com", "threads.net", "primevideo.com", "netflix.com",
            "crunchyroll.com", "disneyplus.com", "justwatch.com", "imdb.it",
        )
    }
}
