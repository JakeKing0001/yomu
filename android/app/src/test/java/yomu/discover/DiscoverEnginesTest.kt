package yomu.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoverEnginesTest {

    private val pirates = TasteItem(1, "Mare Grande", listOf("Azione", "Avventura", "Shounen"))
    private val romance = TasteItem(2, "Cuori di Vetro", listOf("Romantico", "Scolastico"))
    private val horror = TasteItem(3, "La Casa Buia", listOf("Horror", "Mistero"))
    private val liked = TasteItem(9, "Rotta Maggiore", listOf("Azione", "Avventura"))

    @Test
    fun `puts what matches the library first and says why`() {
        val ranked = TasteEngine.rank(listOf(romance, horror, pirates), liked = listOf(liked))

        assertEquals(pirates.id, ranked.first().item.id)
        assertEquals(liked.title, ranked.first().because)
    }

    @Test
    fun `a new reader gets the catalogue by popularity`() {
        val ranked = TasteEngine.rank(listOf(romance, horror, pirates), liked = emptyList())

        assertEquals(listOf(romance.id, horror.id, pirates.id), ranked.map { it.item.id })
    }

    @Test
    fun `rejected genres sink and rejected titles disappear`() {
        val rejected = TasteItem(8, "Altra Casa", listOf("Horror"))
        val ranked = TasteEngine.rank(
            listOf(horror, romance, rejected),
            liked = emptyList(),
            disliked = listOf(rejected),
        )

        assertEquals(romance.id, ranked.first().item.id)
        assertTrue(ranked.none { it.item.id == rejected.id })
    }

    private data class Series(val title: String, val plot: String)

    private val catalogue = listOf(
        Series("One Piece", "Un giovane pirata mangia un frutto che gli permette di allungarsi a piacimento."),
        Series("Berserk", "Un mercenario con una spada enorme cerca vendetta in un mondo oscuro."),
        Series("Haikyu", "Un ragazzo basso sogna di diventare un campione di pallavolo."),
    )

    private fun find(query: String) = MemorySearch.search(query, catalogue) {
        SearchFields(it.title, emptyList(), it.plot)
    }.map { it.title }

    @Test
    fun `finds a series from a loose memory despite different word forms`() {
        assertEquals("One Piece", find("quello del pirata che si allunga").first())
        assertEquals("Berserk", find("mercenari con la spada").first())
    }

    @Test
    fun `ignores accents and returns nothing for unrelated words`() {
        assertEquals("Haikyu", find("pallavòlo").first())
        assertTrue(find("astronave").isEmpty())
    }
}
