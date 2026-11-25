package yomu.source

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.extension.model.ContentWarning
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import yomu.search.SearchPack
import kotlin.time.Duration.Companion.seconds

data class BackupHit(val manga: Manga, val chapter: Chapter, val sourceName: String)

/** Looks for the same chapter of the same series on the other installed sources. */
@Inject
class BackupSourceFinder(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val migrateManga: MigrateMangaUseCase,
    private val searchPack: SearchPack,
) {

    suspend fun find(mangaId: Long, chapterId: Long, onSource: (String) -> Unit): BackupHit? =
        withContext(Dispatchers.IO) {
            val manga = getManga.await(mangaId) ?: return@withContext null
            val chapter = getChapter.await(chapterId) ?: return@withContext null
            if (!chapter.isRecognizedNumber) return@withContext null
            val language = sourceManager.get(manga.source)?.lang
            val engine = SmartSourceSearchEngine(null)

            candidates(manga, language).firstNotNullOfOrNull { source ->
                onSource(source.name)
                try {
                    withTimeoutOrNull(SOURCE_TIMEOUT) { search(engine, source, manga, chapter.chapterNumber) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Backup search failed on ${source.name}" }
                    null
                }
            }
        }

    private suspend fun candidates(manga: Manga, language: String?): List<HttpSource> {
        val enabled = sourcePreferences.enabledLanguages.get()
        val fromPack = searchPack.load(
            searchPack.entries().filter { entry ->
                entry.sources.any { it.lang == language }
            },
        )
            .filterIsInstance<HttpSource>()
            .filter { it.lang == language }
        return (sourceManager.getOnlineSources() + fromPack)
            .distinctBy { it.id }
            .filter { it.id != manga.source && (it.lang == language || it.lang in enabled) }
            .sortedBy { if (it.lang == language) 0 else 1 }
    }

    private suspend fun search(
        engine: SmartSourceSearchEngine,
        source: HttpSource,
        manga: Manga,
        number: Double,
    ): BackupHit? {
        val found = engine.regularSearch(source, manga.title) ?: return null
        val local = networkToLocalManga(found)
        updateMangaFromRemote(source, local, fetchDetails = true, fetchChapters = true).getOrThrow()
        val chapter = getChaptersByMangaId.await(local.id)
            .firstOrNull { it.isRecognizedNumber && it.chapterNumber == number }
            ?: return null
        // A source can list a chapter whose pages are gone, which is the failure being escaped.
        if (source.getPageList(chapter.toSChapter()).isEmpty()) return null
        // A search-only source becomes a real one, so the reader can open the chapter.
        if (sourceManager.get(source.id) == null && searchPack.install(source.id) == null) return null
        return BackupHit(getManga.await(local.id) ?: local, chapter, source.name)
    }

    /** Sources in the series' language that aren't installed yet, offered when none of the installed ones has it. */
    suspend fun installable(mangaId: Long): List<Extension.Available> = withContext(Dispatchers.IO) {
        val language =
            getManga.await(mangaId)?.let { sourceManager.get(it.source)?.lang } ?: return@withContext emptyList()
        extensionManager.findAvailableExtensions()
        val installed = extensionManager.getLoadedExtensions().mapTo(HashSet()) { it.pkgName }
        withTimeoutOrNull(LIST_TIMEOUT) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }.orEmpty()
            // Multi-language catalogues and adult sources aren't a sensible one-tap suggestion for a series.
            .filter { it.pkgName !in installed && it.lang == language && it.contentWarning == ContentWarning.SAFE }
            .take(MAX_SUGGESTIONS)
    }

    suspend fun install(extension: Extension.Available): Boolean = withContext(Dispatchers.IO) {
        val result = extensionManager.installExtension(extension).first(InstallStep::isCompleted)
        if (result != InstallStep.Installed) return@withContext false
        withTimeoutOrNull(LIST_TIMEOUT) {
            extensionManager.loadedExtensionsFlow.first { loaded -> loaded.any { it.pkgName == extension.pkgName } }
        } != null
    }

    suspend fun moveSeries(currentMangaId: Long, hit: BackupHit) {
        val current = getManga.await(currentMangaId) ?: return
        migrateManga(current, hit.manga, replace = current.favorite)
    }

    private companion object {
        val SOURCE_TIMEOUT = 25.seconds
        val LIST_TIMEOUT = 20.seconds
        const val MAX_SUGGESTIONS = 4
    }
}
