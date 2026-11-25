package yomu.reader

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class MemoryChapter(val number: Double, val pages: List<String>)

/** The words of the pages actually opened, kept on the device, one file per manga. */
@Inject
@SingleIn(AppScope::class)
class ReadingMemory(private val context: Context) {

    private val mutex = Mutex()

    private data class Entry(val chapterId: Long, val chapterNumber: Double, val page: Int, val text: String)

    private fun file(mangaId: Long) = File(File(context.filesDir, "yomu_memory").apply { mkdirs() }, "$mangaId.tsv")

    suspend fun add(mangaId: Long, chapterId: Long, chapterNumber: Double, page: Int, text: String) {
        val clean = text.replace('\t', ' ').replace('\r', ' ').replace("\n", " / ").trim()
        if (clean.length < MIN_PAGE_CHARS) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val entries = read(mangaId)
                    .filterNot { it.chapterId == chapterId && it.page == page }
                    .plus(Entry(chapterId, chapterNumber, page, clean))
                    .sortedWith(compareBy(Entry::chapterNumber, Entry::page))
                var total = 0
                val kept = entries.asReversed().takeWhile {
                    total += it.text.length
                    total <= MAX_CHARS
                }.asReversed()
                file(mangaId).writeText(
                    kept.joinToString("\n") { "${it.chapterId}\t${it.chapterNumber}\t${it.page}\t${it.text}" },
                )
            }
        }
    }

    /** What was read up to the given position, so later chapters read earlier can't leak in. */
    suspend fun chaptersUpTo(mangaId: Long, chapterNumber: Double, page: Int): List<MemoryChapter> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                read(mangaId)
                    .filter {
                        it.chapterNumber < chapterNumber || (it.chapterNumber == chapterNumber && it.page <= page)
                    }
                    .groupBy { it.chapterNumber }
                    .toSortedMap()
                    .map { (number, pages) -> MemoryChapter(number, pages.sortedBy(Entry::page).map(Entry::text)) }
            }
        }
    }

    suspend fun pagesOf(mangaId: Long, chapterId: Long): Set<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            read(mangaId).filter { it.chapterId == chapterId }.mapTo(HashSet(), Entry::page)
        }
    }

    suspend fun readRecapCache(mangaId: Long): String? = withContext(Dispatchers.IO) {
        recapFile(mangaId).takeIf(File::exists)?.readText()
    }

    suspend fun writeRecapCache(mangaId: Long, content: String) = withContext(Dispatchers.IO) {
        recapFile(mangaId).writeText(content)
    }

    private fun recapFile(mangaId: Long) = File(file(mangaId).parentFile, "$mangaId.recap.json")

    private fun read(mangaId: Long): List<Entry> {
        val file = file(mangaId)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val fields = line.split('\t', limit = 4)
            if (fields.size != 4) return@mapNotNull null
            Entry(
                chapterId = fields[0].toLongOrNull() ?: return@mapNotNull null,
                chapterNumber = fields[1].toDoubleOrNull() ?: return@mapNotNull null,
                page = fields[2].toIntOrNull() ?: return@mapNotNull null,
                text = fields[3],
            )
        }
    }

    private companion object {
        const val MIN_PAGE_CHARS = 4
        const val MAX_CHARS = 120_000
    }
}
