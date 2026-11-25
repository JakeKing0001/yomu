package yomu.volumes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

data class VolumeGroup<T>(val label: String?, val items: List<T>)

/** Chapters listed under the volumes of a source page, by chapter id and by chapter number. */
data class SourceVolumes(val byId: Map<String, String>, val byNumber: Map<String, String>) {
    val isEmpty: Boolean get() = byId.isEmpty() && byNumber.isEmpty()
}

object VolumeParser {

    // A bare "v2" usually means the second release of a chapter, so it isn't read as a volume.
    private val IN_NAME =
        Regex("""(?:^|[\s\[(\-])(?:vol(?:ume)?|tomo)\b\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val CHAPTER_NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val READ_ID = Regex("""/read/([0-9a-fA-F]{8,})""")

    fun fromName(name: String): String? {
        return IN_NAME.find(name)?.groupValues?.get(1)?.let(::cleanNumber)
    }

    fun chapterKey(number: Double): String = number.toString().removeSuffix(".0")

    fun chapterIdFromUrl(url: String): String? = READ_ID.find(url)?.groupValues?.get(1)

    fun fromMangaworld(html: String): SourceVolumes {
        val byId = HashMap<String, String>()
        val byNumber = HashMap<String, String>()
        Jsoup.parse(html).select("[data-chapters-list] .volume").forEach { volume ->
            val title = volume.selectFirst(".volume-name")?.text().orEmpty()
            val label = fromName(title) ?: title.trim().takeIf(String::isNotEmpty) ?: return@forEach
            volume.select(".ch-row").forEach { row ->
                row.attr("data-chapter").takeIf(String::isNotEmpty)?.let { byId[it] = label }
                row.selectFirst(".ch-name")?.text()
                    ?.let(CHAPTER_NUMBER::find)
                    ?.value
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()
                    ?.let { byNumber[chapterKey(it)] = label }
            }
        }
        return SourceVolumes(byId, byNumber)
    }

    /** MangaDex's aggregate; its "none" bucket holds chapters not collected in a volume yet. */
    fun fromAggregate(text: String): SourceVolumes {
        val volumes = Json.parseToJsonElement(text).jsonObject["volumes"] as? kotlinx.serialization.json.JsonObject
            ?: return SourceVolumes(emptyMap(), emptyMap())
        val byNumber = HashMap<String, String>()
        volumes.forEach { (volume, content) ->
            if (volume == "none") return@forEach
            val chapters = content.jsonObject["chapters"] as? kotlinx.serialization.json.JsonObject
                ?: return@forEach
            chapters.keys.forEach { chapter ->
                chapter.toDoubleOrNull()?.let { byNumber[chapterKey(it)] = cleanNumber(volume) }
            }
        }
        return SourceVolumes(emptyMap(), byNumber)
    }

    /** Keeps the list's order, so a descending chapter list gives descending volumes. */
    fun <T> group(items: List<T>, labelOf: (T) -> String?): List<VolumeGroup<T>> {
        val groups = LinkedHashMap<String?, MutableList<T>>()
        items.forEach { groups.getOrPut(labelOf(it)) { ArrayList() } += it }
        return groups.map { (label, chapters) -> VolumeGroup(label, chapters) }
    }

    private fun cleanNumber(value: String): String {
        val number = value.toDoubleOrNull() ?: return value
        return chapterKey(number)
    }
}
