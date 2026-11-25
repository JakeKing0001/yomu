package yomu.search

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class SmartSearchPartsTest {

    @Test
    fun `an Italian anime title leads to the manga it adapts`() {
        val answer = """
            {"data":{"Page":{"media":[
              {"type":"ANIME","title":{"romaji":"Shingeki no Kyojin","english":"Attack on Titan","native":"進撃の巨人"},
               "synonyms":["L'attacco dei giganti","AoT"],
               "relations":{"edges":[
                 {"relationType":"SEQUEL","node":{"type":"ANIME","title":{"romaji":"Shingeki no Kyojin 2"},"synonyms":[]}},
                 {"relationType":"SOURCE","node":{"type":"MANGA","title":{"romaji":"Shingeki no Kyojin","english":"Attack on Titan"},"synonyms":[]}}
               ]}}
            ]}}}
        """.trimIndent()

        assertEquals(
            listOf("Shingeki no Kyojin", "Attack on Titan"),
            TitleExpander.parseAniList(answer, "L'attacco dei giganti"),
        )
    }

    @Test
    fun `an unrelated AniList result adds no titles`() {
        val answer = """{"data":{"Page":{"media":[{"type":"MANGA","title":{"romaji":"Berserk"},"synonyms":[]}]}}}"""

        assertTrue(TitleExpander.parseAniList(answer, "L'attacco dei giganti").isEmpty())
    }

    @Test
    fun `MangaDex alternative titles find the main one`() {
        val answer = """
            {"data":[{"attributes":{"title":{"en":"Attack on Titan"},
              "altTitles":[{"ja-ro":"Shingeki no Kyojin"},{"it":"L'attacco dei giganti"}]}}]}
        """.trimIndent()

        assertEquals(listOf("Attack on Titan"), TitleExpander.parseMangaDex(answer, "L'attacco dei giganti"))
    }

    @Test
    fun `titles match regardless of case, accents and punctuation`() {
        assertTrue(TitleMatch.isStrong(TitleMatch.score("Shingeki No Kyojin!", listOf("shingeki no kyojin"))))
        assertTrue(TitleMatch.isStrong(TitleMatch.score("One Piece (Colored)", listOf("One Piece"))))
        assertFalse(TitleMatch.isStrong(TitleMatch.score("One Punch-Man", listOf("One Piece"))))
    }

    @Test
    fun `the pack follows the content setting and always keeps the big catalogues`() {
        val defaults = setOf(ContentWarning.SAFE, ContentWarning.MIXED)
        assertTrue(SearchPack.isWanted(ContentWarning.SAFE, listOf("it"), defaults))
        assertTrue(SearchPack.isWanted(ContentWarning.MIXED, listOf("en"), defaults))
        assertFalse(SearchPack.isWanted(ContentWarning.NSFW, listOf("en"), defaults))
        assertFalse(SearchPack.isWanted(ContentWarning.NSFW, listOf("en"), defaults + ContentWarning.NSFW))
        assertTrue(SearchPack.isWanted(ContentWarning.MIXED, listOf("en", "it"), setOf(ContentWarning.SAFE)))
    }

    @Test
    fun `extensions that open a captcha in a dialog are left out of the search pack`() {
        val both = "xxLandroid/app/Dialog;yyLandroid/webkit/WebView;zz".encodeToByteArray()
        val webViewOnly = "Landroid/webkit/WebView;".encodeToByteArray()

        assertTrue(SearchPack.showsDialogWithWebView(both))
        assertFalse(SearchPack.showsDialogWithWebView(webViewOnly))
    }

    @Test
    fun `a plot description is told apart from a title and the guesses are cleaned`() {
        assertTrue(TitleExpander.isDescription("due ragazzi a scuola e una ragazza piccola ma feroce"))
        assertFalse(TitleExpander.isDescription("Toradora"))
        assertEquals(
            listOf("Toradora!", "Kaguya-sama wa Kokurasetai", "Golden Time"),
            TitleExpander.parseGuesses(
                "1. Toradora! (2008)\n- \"Kaguya-sama wa Kokurasetai\"\nGolden Time - anime\nExtra",
            ),
        )
    }

    @Test
    fun `result titles give the series they are about, the most repeated first`() {
        val titles = listOf(
            "Taiga Aisaka | Toradora Wiki | Fandom",
            "Taiga Aisaka | Toradora! | Personaggio | Anibase.Net",
            "I 10 migliori anime scolastici - Lista",
        )

        val candidates = TitleExpander.descriptionCandidates(titles)

        assertEquals("Taiga Aisaka", candidates[0])
        assertTrue(candidates.take(3).any { TitleMatch.normalize(it) == "toradora" })
        assertFalse(candidates.any { it.contains("migliori", ignoreCase = true) && it.contains("Lista") })
    }

    @Test
    fun `a source failing twice is skipped for a day and tried again after`() {
        val saved = slot<Set<String>>()
        val store = mockk<Preference<Set<String>>>(relaxed = true)
        every { store.get() } returns emptySet()
        every { store.set(capture(saved)) } returns Unit
        var now = 0L
        val health = SourceHealth(store) { now }

        health.failed(7)
        assertTrue(health.isUsable(7))
        health.failed(7)
        assertFalse(health.isUsable(7))
        now += 25L * 60 * 60 * 1000
        assertTrue(health.isUsable(7))
        health.worked(7)
        assertTrue(saved.captured.isEmpty())
    }

    @Test
    fun `adult extensions and genres stay out of searches`() {
        assertTrue(PackEntry("p", "n", 1, emptyList(), 0, warning = "NSFW").isAdult)
        assertFalse(PackEntry("p", "n", 1, emptyList(), 0, warning = "MIXED").isAdult)
        assertTrue(SmartSearch.isAdultGenre("Romance, Hentai"))
        assertTrue(SmartSearch.isAdultGenre("Smut, Drama"))
        assertFalse(SmartSearch.isAdultGenre("Action, Fantasy, Adventure"))
        assertFalse(SmartSearch.isAdultGenre(null))
    }
}
