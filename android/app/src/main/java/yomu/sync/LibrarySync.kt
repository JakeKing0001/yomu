package yomu.sync

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences

data class SyncOutcome(val series: Int, val added: Int, val removed: Int, val skipped: Int)

/** Keeps library and progress in one file the user picks, typically on Google Drive through the system picker. */
@Inject
@SingleIn(AppScope::class)
class LibrarySync(
    private val context: Context,
    private val preferences: YomuPreferences,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val isConfigured: Boolean get() = preferences.syncFile.get().isNotEmpty()

    suspend fun sync(): SyncOutcome? = withContext(Dispatchers.IO) {
        val uri = preferences.syncFile.get().takeIf(String::isNotEmpty)?.let(Uri::parse) ?: return@withContext null
        mutex.withLock {
            val remote = readSnapshot(uri)
            val local = localEntries()
            val plan = SyncEngine.merge(local, remote.entries, preferences.syncKnown.get())

            var skipped = 0
            plan.addLocally.forEach { if (!addSeries(it)) skipped++ }
            plan.updateLocally.forEach { applyProgress(it) }
            plan.removeLocally.forEach { key -> removeSeries(local.first { it.key == key }) }

            val snapshot = SyncSnapshot(updatedAt = System.currentTimeMillis(), entries = plan.merged)
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(json.encodeToString(snapshot).encodeToByteArray())
            } ?: error("Sync file can't be written")

            // A series whose source isn't installed here stays out, so it's offered again rather than deleted.
            val present = localEntries().mapTo(HashSet(), SyncEntry::key)
            preferences.syncKnown.set(plan.merged.map(SyncEntry::key).filterTo(HashSet()) { it in present })
            preferences.syncLastAt.set(snapshot.updatedAt)
            SyncOutcome(plan.merged.size, plan.addLocally.size - skipped, plan.removeLocally.size, skipped)
        }
    }

    private fun readSnapshot(uri: Uri): SyncSnapshot {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("Sync file can't be read")
        if (text.isBlank()) return SyncSnapshot()
        return json.decodeFromString(text)
    }

    private suspend fun localEntries(): List<SyncEntry> {
        return getFavorites.await().map { manga ->
            val chapters = getChaptersByMangaId.await(manga.id)
                .filter { it.read || it.bookmark || it.lastPageRead > 0 }
                .associate { it.url to SyncChapter(it.read, it.lastPageRead.toInt(), it.bookmark) }
            SyncEntry(manga.source, manga.url, manga.title, manga.thumbnailUrl, chapters)
        }
    }

    private suspend fun addSeries(entry: SyncEntry): Boolean {
        val source = sourceManager.get(entry.sourceId) ?: return false
        val manga = getMangaByUrlAndSourceId.await(entry.url, entry.sourceId)
            ?: networkToLocalManga(
                Manga.create().copy(
                    source = entry.sourceId,
                    url = entry.url,
                    title = entry.title,
                    thumbnailUrl = entry.thumbnail,
                ),
            )
        updateManga.awaitUpdateFavorite(manga.id, true)
        try {
            updateMangaFromRemote(source, manga, fetchDetails = true, fetchChapters = true).getOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next sync finds it in both libraries and restores the progress then.
            logcat(LogPriority.WARN, e) { "Couldn't fetch the chapters of a synced series" }
        }
        applyProgress(entry)
        return true
    }

    private suspend fun applyProgress(entry: SyncEntry) {
        val manga = getMangaByUrlAndSourceId.await(entry.url, entry.sourceId) ?: return
        val updates = getChaptersByMangaId.await(manga.id).mapNotNull { chapter ->
            val wanted = entry.chapters[chapter.url] ?: return@mapNotNull null
            val isSame = chapter.read == wanted.read &&
                chapter.bookmark == wanted.bookmark &&
                chapter.lastPageRead.toInt() == wanted.page
            if (isSame) return@mapNotNull null
            ChapterUpdate(chapter.id) {
                read = wanted.read
                bookmark = wanted.bookmark
                lastPageRead = wanted.page
            }
        }
        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
    }

    private suspend fun removeSeries(entry: SyncEntry) {
        val manga = getMangaByUrlAndSourceId.await(entry.url, entry.sourceId) ?: return
        updateManga.awaitUpdateFavorite(manga.id, false)
    }
}
