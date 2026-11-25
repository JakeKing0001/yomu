package yomu.chapters

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import yomu.search.SourceReplacer
import kotlin.time.Duration.Companion.minutes

/**
 * Runs after a library update over the series it updated: one whose source is gone or whose page no
 * longer exists moves to a source that has it, and every one is completed with the chapters other
 * sources and the web have and its own source lacks.
 */
class LibraryCompleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var getManga: GetManga

    @Inject lateinit var sourceManager: SourceManager

    @Inject lateinit var sourceReplacer: SourceReplacer

    @Inject lateinit var chapterFiller: ChapterFiller

    override suspend fun doWork(): Result {
        graph.inject(this)
        val ids = inputData.getLongArray(KEY_IDS)?.toList().orEmpty()
        val gone = inputData.getLongArray(KEY_GONE)?.toSet().orEmpty()
        val deadline = System.currentTimeMillis() + RUN_BUDGET.inWholeMilliseconds
        var added = inputData.getInt(KEY_ADDED, 0)
        var replaced = inputData.getInt(KEY_REPLACED, 0)

        ids.forEachIndexed { index, id ->
            if (System.currentTimeMillis() > deadline) {
                // A worker may only run for minutes, so a large library is gone through over several runs.
                start(applicationContext, ids.drop(index), gone, added, replaced, ExistingWorkPolicy.APPEND_OR_REPLACE)
                return Result.success()
            }
            try {
                var manga = getManga.await(id) ?: return@forEachIndexed
                if (manga.source == LocalSource.ID || !manga.favorite) return@forEachIndexed
                if (id in gone || sourceManager.get(manga.source) !is HttpSource) {
                    sourceReplacer.replace(manga)?.let {
                        manga = it
                        replaced++
                    }
                }
                added += chapterFiller.fill(manga)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Couldn't complete series $id" }
            }
        }
        if (added > 0 || replaced > 0) notifyDone(added, replaced)
        return Result.success()
    }

    private fun notifyDone(added: Int, replaced: Int) {
        val context = applicationContext
        val parts = listOfNotNull(
            context.pluralStringResource(MR.plurals.yomu_chapters_borrowed, added, added).takeIf { added > 0 },
            context.pluralStringResource(MR.plurals.yomu_sources_replaced, replaced, replaced).takeIf { replaced > 0 },
        )
        context.notify(NOTIFICATION_ID, Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.yomu_library_completed))
            setContentText(parts.joinToString(" · "))
            setAutoCancel(true)
        }
    }

    companion object {
        private const val NAME = "YomuLibraryComplete"
        private const val KEY_IDS = "ids"
        private const val KEY_GONE = "gone"
        private const val KEY_ADDED = "added"
        private const val KEY_REPLACED = "replaced"
        private const val NOTIFICATION_ID = -8_102
        private val RUN_BUDGET = 8.minutes

        /** An update error that means the series' page is gone, not that the site is briefly down. */
        private val GONE = Regex("""\b(404|410)\b""")

        fun isGone(error: String?): Boolean = error != null && GONE.containsMatchIn(error)

        fun start(
            context: Context,
            ids: List<Long>,
            gone: Collection<Long>,
            added: Int = 0,
            replaced: Int = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            if (ids.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<LibraryCompleteWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(
                    workDataOf(
                        KEY_IDS to ids.toLongArray(),
                        KEY_GONE to gone.toLongArray(),
                        KEY_ADDED to added,
                        KEY_REPLACED to replaced,
                    ),
                )
                .build()
            context.workManager.enqueueUniqueWork(NAME, policy, request)
        }
    }
}
