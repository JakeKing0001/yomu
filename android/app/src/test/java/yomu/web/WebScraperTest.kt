package yomu.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebScraperTest {

    @Test
    fun `finds the chapters a series page links to, newest first`() {
        val html = """
            <h1>Writer's Legacy</h1>
            <a href="/series/writers-legacy/chapter-1">Chapter 1</a>
            <a href="/series/writers-legacy/chapter-2/">Chapter 2</a>
            <a href="/series/writers-legacy/c/abc">Capitolo 3</a>
            <a href="https://other.site/chapter-9">Chapter 9</a>
            <a href="/series/other">Other series</a>
            <a href="/series/other-series/chapter-50">Chapter 50</a>
            <a href="/series/blue-box">Episodio 8</a>
        """.trimIndent()

        val chapters = WebScraper.chapters(WebScraper.parse(html, "https://example.org/series/writers-legacy"))

        assertEquals(listOf(3f, 2f, 1f), chapters.map { it.number })
        assertEquals("https://example.org/series/writers-legacy/c/abc", chapters.first().url)
    }

    @Test
    fun `keeps the run of chapter images and drops logos and thumbnails`() {
        val html = """
            <img src="/images/logo.png">
            <img data-src="https://cdn.site/series/1/01.webp" src="data:image/gif;base64,R0">
            <img data-src="https://cdn.site/series/1/02.webp">
            <img data-src="https://cdn.site/series/1/03.webp">
            <img src="https://cdn.site/thumbs/other.jpg">
        """.trimIndent()

        val pages = WebScraper.pages(WebScraper.parse(html, "https://site/read/1"))

        assertEquals((1..3).map { "https://cdn.site/series/1/0$it.webp" }, pages)
    }

    @Test
    fun `reads images listed in a script when the page draws them later`() {
        val images = (1..3).joinToString(",") { "\"https:\\/\\/img.site\\/c\\/$it.jpg\"" }
        val html = "<script>var images = [$images]</script>"

        assertEquals(3, WebScraper.pages(WebScraper.parse(html, "https://site/read/1")).size)
    }

    @Test
    fun `cleans a page title down to the series name`() {
        assertEquals("Writer's Legacy", WebScraper.cleanTitle("Read Writer's Legacy Manhwa Online - Vortex Scans"))
    }

    @Test
    fun `unwraps DuckDuckGo result links and recognizes Telegram channels`() {
        val target = "https%3A%2F%2Ft.me%2Fs%2Fmangachannel"
        val html = """<a class="result__a" href="//duckduckgo.com/l/?uddg=$target&rut=x">x</a>"""

        val results = WebScraper.searchResults(html)

        assertEquals(listOf("https://t.me/s/mangachannel"), results)
        assertEquals("mangachannel", WebScraper.telegramChannel(results.single()))
        assertEquals(null, WebScraper.telegramChannel("https://t.me/joinchat/abc"))
    }

    @Test
    fun `reads the pictures of public Telegram posts`() {
        val html = """
            <div class="tgme_widget_message" data-post="mangachannel/42">
              <a class="tgme_widget_message_photo_wrap" style="width:100px;background-image:url('https://cdn.tg/a.jpg')"></a>
              <div class="tgme_widget_message_text">One Piece capitolo 1</div>
            </div>
            <div class="tgme_widget_message" data-post="mangachannel/43"><div class="tgme_widget_message_text">Solo testo</div></div>
        """.trimIndent()

        val posts = WebScraper.telegramPosts(WebScraper.parse(html, "https://t.me/s/mangachannel"))

        assertEquals(1, posts.size)
        assertEquals(42L, posts.single().id)
        assertTrue(posts.single().photos.single().endsWith("a.jpg"))
    }

    @Test
    fun `a chapter page knows its number`() {
        assertEquals(12f, WebScraper.chapterNumberOf("https://site.it/manga/viral-hit/capitolo-12/", "Viral Hit"))
        assertEquals(3.5f, WebScraper.chapterNumberOf("https://site.it/read/abc", "Viral Hit Chapter 3.5 - Site"))
        assertEquals(null, WebScraper.chapterNumberOf("https://site.it/manga/viral-hit/", "Viral Hit"))
    }

    @Test
    fun `a web series keeps its title and sites`() {
        val series = WebSource.WebSeries("Writer's Legacy | 2", listOf("https://a.it/x/1", "https://b.com/y?c=1"))
        assertEquals(series, WebSource.WebSeries.parse(series.toString()))
        assertEquals(null, WebSource.WebSeries.parse("https://a.it/x/1"))
    }
}
