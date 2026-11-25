package yomu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaAskTest {

    private val lines = listOf(
        AskLine(1.0, "Il capitano dei pirati si chiama Shanks"),
        AskLine(1.0, "La padrona dell'osteria si chiama Makino"),
        AskLine(2.0, "Rufy vuole diventare il re dei pirati"),
    )

    @Test
    fun `finds the lines that mention what was asked`() {
        val chapters = listOf(
            MemoryChapter(1.0, listOf("La padrona dell'osteria si chiama Makino / Oggi il mare e' calmo")),
        )

        val found = MangaAsk.relevantLines("Chi è Makino?", chapters)

        assertTrue(found.any { it.text.contains("Makino") })
    }

    @Test
    fun `accepts an answer backed by the lines it cites`() {
        val answer = MangaAsk.parse("RISPOSTA: Makino è la padrona dell'osteria.\nRIGHE: 2", lines)

        assertNotNull(answer)
        assertEquals("La padrona dell'osteria si chiama Makino", answer!!.quotes.single().text)
    }

    @Test
    fun `rejects an unknown answer, a missing citation and an invented name`() {
        assertNull(MangaAsk.parse("NON LO SO", lines))
        assertNull(MangaAsk.parse("RISPOSTA: Makino è la padrona dell'osteria.", lines))
        assertNull(MangaAsk.parse("RISPOSTA: Il capitano dei pirati incontra Barbanera.\nRIGHE: 1", lines))
    }
}
