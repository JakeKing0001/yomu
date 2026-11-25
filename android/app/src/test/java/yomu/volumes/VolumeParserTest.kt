package yomu.volumes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VolumeParserTest {

    @Test
    fun `reads the volume from common chapter names`() {
        assertEquals("3", VolumeParser.fromName("Vol.3 Ch.12"))
        assertEquals("12", VolumeParser.fromName("Volume 12 - Capitolo 100"))
        assertEquals("7", VolumeParser.fromName("[Tomo 07] Capitolo 3"))
    }

    @Test
    fun `does not mistake other words or a release version for a volume`() {
        assertNull(VolumeParser.fromName("Capitolo 12 v2"))
        assertNull(VolumeParser.fromName("La voluta 3"))
        assertNull(VolumeParser.fromName("Capitolo 1199"))
    }

    @Test
    fun `reads the volumes of a Mangaworld page`() {
        val html = """
            <div data-chapters-list>
              <div class="volume"><span class="volume-name">Volume 117</span>
                <ul><li class="ch-row" data-chapter="6b032279179d77b59c06a674">
                  <span class="ch-name">Capitolo 1199</span></li></ul></div>
              <div class="volume"><span class="volume-name">Volume 01</span>
                <ul><li class="ch-row" data-chapter="5fa0c6c52b64e91ec5ded7b3">
                  <span class="ch-name">Capitolo 1</span></li></ul></div>
            </div>
        """.trimIndent()

        val volumes = VolumeParser.fromMangaworld(html)

        assertEquals("117", volumes.byId["6b032279179d77b59c06a674"])
        assertEquals("1", volumes.byNumber["1"])
    }

    @Test
    fun `reads MangaDex volumes and leaves uncollected chapters out`() {
        val text = """
            {"volumes":{
              "none":{"chapters":{"1199":{"chapter":"1199"}}},
              "116":{"chapters":{"1190":{"chapter":"1190"},"1189.5":{"chapter":"1189.5"}}}
            }}
        """.trimIndent()

        val volumes = VolumeParser.fromAggregate(text)

        assertEquals("116", volumes.byNumber["1190"])
        assertEquals("116", volumes.byNumber["1189.5"])
        assertNull(volumes.byNumber["1199"])
    }

    @Test
    fun `groups keep the list order and put chapters without a volume together`() {
        val labels = mapOf(3 to "2", 2 to "2", 1 to "1")

        val groups = VolumeParser.group(listOf(5, 4, 3, 2, 1)) { labels[it] }

        assertEquals(listOf(null, "2", "1"), groups.map { it.label })
        assertEquals(listOf(5, 4), groups.first().items)
    }
}
