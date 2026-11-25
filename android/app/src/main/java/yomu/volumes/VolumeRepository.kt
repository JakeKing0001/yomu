package yomu.volumes

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.search.TitleExpander
import java.io.File
import java.text.Normalizer

/** Finds which volume each chapter belongs to: chapter names first, then the source page, then MangaDex. */
@Inject
@SingleIn(AppScope::class)
class VolumeRepository(
    private val context: Context,
    private val network: NetworkHelper,
    private val sourceManager: SourceManager,
) {

    @Serializable
    private data class Stored(
        val updatedAt: Long,
        val chapterCount: Int,
        val byId: Map<String, String>,
        val byNumber: Map<String, String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun labels(manga: Manga, chapters: List<Chapter>): Map<Long, String> = withContext(Dispatchers.IO) {
        val fromNames = chapters.mapNotNull { chapter ->
            VolumeParser.fromName(chapter.name)?.let { chapter.id to it }
        }.toMap()
        if (fromNames.isNotEmpty() && fromNames.size * 2 >= chapters.size) {
            val byNumber = chapters.mapNotNull { chapter ->
                fromNames[chapter.id]?.let { VolumeParser.chapterKey(chapter.chapterNumber) to it }
            }.toMap()
            write(manga.id, chapters.size, SourceVolumes(emptyMap(), byNumber))
            return@withContext fromNames
        }

        val stored = read(manga.id)?.takeIf {
            it.chapterCount == chapters.size && System.currentTimeMillis() - it.updatedAt < MAX_AGE_MS
        }
        val volumes = stored?.let { SourceVolumes(it.byId, it.byNumber) }
            ?: fetch(manga)?.also { write(manga.id, chapters.size, it) }
            ?: read(manga.id)?.let { SourceVolumes(it.byId, it.byNumber) }
            ?: return@withContext emptyMap()

        chapters.mapNotNull { chapter ->
            val label = VolumeParser.chapterIdFromUrl(chapter.url)?.let(volumes.byId::get)
                ?: volumes.byNumber[VolumeParser.chapterKey(chapter.chapterNumber)]
            label?.let { chapter.id to it }
        }.toMap()
    }

    /** The volume of each chapter number already known, so a recap can use them without any request. */
    suspend fun knownByNumber(mangaId: Long): Map<String, String> = withContext(Dispatchers.IO) {
        read(mangaId)?.byNumber.orEmpty()
    }

    private suspend fun fetch(manga: Manga): SourceVolumes? {
        return try {
            val source = sourceManager.get(manga.source) as? HttpSource
            val fromSource = source
                ?.takeIf { it.name.equals(MANGAWORLD, ignoreCase = true) }
                ?.let { mangaworld ->
                    val url = mangaworld.getMangaUrl(manga.toSManga())
                    mangaworld.client.newCall(GET(url, mangaworld.headers)).await().use { response ->
                        if (response.isSuccessful) VolumeParser.fromMangaworld(response.body.string()) else null
                    }
                }
            fromSource?.takeUnless(SourceVolumes::isEmpty) ?: fromMangaDex(manga.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Couldn't find the volumes" }
            null
        }
    }

    private suspend fun fromMangaDex(title: String): SourceVolumes? {
        val search = "$MANGADEX/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("limit", "5")
            .build()
        val found = network.client.newCall(GET(search, TitleExpander.API_HEADERS)).await().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val wanted = normalized(title)
        val results = Json.parseToJsonElement(found).jsonObject["data"] as? JsonArray ?: return null
        val id = results.firstNotNullOfOrNull { element ->
            val manga = element.jsonObject
            val attributes = manga["attributes"]?.jsonObject ?: return@firstNotNullOfOrNull null
            val names = titles(attributes["title"]) +
                (attributes["altTitles"] as? JsonArray).orEmpty().flatMap(::titles)
            // A loose match would show another series' volumes, so only the exact title counts.
            manga["id"]?.jsonPrimitive?.contentOrNull?.takeIf { names.any { normalized(it) == wanted } }
        } ?: return null
        return network.client.newCall(
            GET("$MANGADEX/manga/$id/aggregate", TitleExpander.API_HEADERS),
        ).await().use { response ->
            if (response.isSuccessful) VolumeParser.fromAggregate(response.body.string()) else null
        }?.takeUnless(SourceVolumes::isEmpty)
    }

    private fun titles(element: kotlinx.serialization.json.JsonElement?): List<String> {
        return (element as? JsonObject)?.values?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }

    private fun normalized(title: String): String {
        return Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).filter(Char::isLetterOrDigit)
    }

    private fun file(mangaId: Long) = File(File(context.filesDir, "yomu_volumes").apply { mkdirs() }, "$mangaId.json")

    private fun read(mangaId: Long): Stored? {
        val file = file(mangaId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrNull()
    }

    private fun write(mangaId: Long, chapterCount: Int, volumes: SourceVolumes) {
        val stored = Stored(System.currentTimeMillis(), chapterCount, volumes.byId, volumes.byNumber)
        file(mangaId).writeText(json.encodeToString(stored))
    }

    private companion object {
        const val MANGAWORLD = "Mangaworld"
        const val MANGADEX = "https://api.mangadex.org"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
