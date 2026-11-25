package yomu.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecapTextTest {

    @Test
    fun `rejoins syllables and repairs misread letters`() {
        assertEquals(listOf("Dannazione!"), RecapText.cleanPage("DAN- NA- ZIO- NE!"))
        assertEquals(
            listOf("Capitano roger! non voglio morire!"),
            RecapText.cleanPage("CAPITAN0 ROGER! NON VOGLIO MORIRE!"),
        )
    }

    @Test
    fun `drops sound effects credits and duplicates`() {
        val page = "BOOOM / !! / ZZZ / SCAN BY MANGAWORLD / VOGLIO DIVENTARE UN PIRATA / voglio diventare un pirata"

        assertEquals(listOf("Voglio diventare un pirata"), RecapText.cleanPage(page))
    }

    @Test
    fun `accepts a sentence built from the lines even with OCR typos in names`() {
        val evidence = listOf("Il capitano dei pirati, shanks detto il rosso", "La padrona dell'osteria, makino")

        assertTrue(RecapText.isSupported("La padrona dell'osteria, Makino, parla con il capitano Shankss.", evidence))
    }

    @Test
    fun `rejects invented facts and invented names`() {
        val evidence = listOf("Ehi, garp-san, hai rifiutato la promozione", "Garp fermati immediatamente")

        assertFalse(RecapText.isSupported("Garp riconosce il protagonista e lo ammonisce.", evidence))
        assertFalse(RecapText.isSupported("Garp rifiuta la promozione insieme a Dragon.", evidence))
    }

    @Test
    fun `rejects copied dialogue and garbled words`() {
        val evidence = listOf("Ehi, garp-san, hai rifiutato ancora la promozione", "Ammira 6uo flotta non ha finito")

        assertFalse(RecapText.isSupported("Ehi, Garp-san, hai rifiutato ancora la promozione.", evidence))
        assertFalse(RecapText.isSupported("Ammira 6uo flotta non ha finito.", evidence))
        assertTrue(RecapText.isSupported("Garp rifiuta ancora la promozione.", evidence))
    }

    @Test
    fun `restores a misread name from the chapter's usual spelling`() {
        val lines = listOf("Parliamo di roger", "Il capitano roger", "Roger ride", "Un contatto con rogtk")

        assertEquals("Un contatto con roger", RecapText.repairChapter(lines).last())
    }

    @Test
    fun `quotes real lines rather than bare reactions`() {
        val lines = listOf("Ce ne andremo verso nord", "Siamo a corto di birra", "Wow! grazie!")

        assertEquals(listOf("Ce ne andremo verso nord", "Siamo a corto di birra"), RecapText.meaningfulLines(lines, 3))
    }

    @Test
    fun `listed characters skip ordinary words and names not in the pages`() {
        val evidence = listOf("La grande flotta di garp", "Al porto arriva shanks", "Il capitano roger")

        assertEquals(
            listOf("Garp", "Shanks", "Roger"),
            RecapText.parseCharacters("PERSONAGGI: Grande, Garp, Shanks, Al, Roger, Zoro", evidence),
        )
    }

    @Test
    fun `repairs a question mark and a d read as p`() {
        assertEquals(
            listOf("Avete sentito le ultime notizie?!", "Questo e l'inizio dell'era dei pirati!"),
            RecapText.cleanPage("Avete sentito le ultime notiziep! / Questo e l'inizio pell'era pei pirati!"),
        )
    }

    @Test
    fun `keeps real words that start or end with p`() {
        assertEquals(
            listOf("La pelle del capo", "Il pirata dice stop"),
            RecapText.cleanPage("La pelle del capo / Il pirata dice stop"),
        )
    }

    @Test
    fun `finds characters without counting words that open a sentence`() {
        val names = RecapText.characters(
            listOf(
                "Un ragazzo del villaggio, Monkey D. Rufy, ascolta il capitano Shanks.",
                "Rufy intende diventare pirata.",
                "La padrona dell'osteria, Makino, parla con lui.",
            ),
        )

        assertEquals(listOf("Monkey D. Rufy", "Shanks", "Makino"), names)
    }

    @Test
    fun `reads the chronicle by chapter`() {
        val parsed = RecapText.parseChronicle(
            "**CAPITOLO 12.5**\n- Prima frase.\n- Seconda frase.\n\nCAPITOLO 13\n• Terza.",
        )

        assertEquals(listOf(12.5 to listOf("Prima frase.", "Seconda frase."), 13.0 to listOf("Terza.")), parsed)
    }

    @Test
    fun `an unusable pick falls back to lines across the chapter`() {
        val lines = List(40) { "riga numero $it" }

        assertEquals(16, RecapText.pickedLines("non saprei", lines, 16).size)
        assertEquals(listOf("riga numero 1", "riga numero 3"), RecapText.pickedLines("2, 4, 99", lines.take(6), 6))
    }
}
