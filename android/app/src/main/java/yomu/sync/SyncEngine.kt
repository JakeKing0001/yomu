package yomu.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncChapter(val read: Boolean = false, val page: Int = 0, val bookmark: Boolean = false)

@Serializable
data class SyncEntry(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val chapters: Map<String, SyncChapter> = emptyMap(),
) {
    val key: String get() = "$sourceId|$url"
}

@Serializable
data class SyncSnapshot(val version: Int = 1, val updatedAt: Long = 0, val entries: List<SyncEntry> = emptyList())

data class SyncPlan(
    val merged: List<SyncEntry>,
    val addLocally: List<SyncEntry>,
    val updateLocally: List<SyncEntry>,
    val removeLocally: Set<String>,
)

object SyncEngine {

    /**
     * Three-way merge against [known], the series both sides had at the last sync: without it a series
     * removed on one device would look new to the other and come back.
     */
    fun merge(local: List<SyncEntry>, remote: List<SyncEntry>, known: Set<String>): SyncPlan {
        val localByKey = local.associateBy(SyncEntry::key)
        val remoteByKey = remote.associateBy(SyncEntry::key)
        val merged = ArrayList<SyncEntry>()
        val add = ArrayList<SyncEntry>()
        val update = ArrayList<SyncEntry>()
        val remove = HashSet<String>()

        (localByKey.keys + remoteByKey.keys).forEach { key ->
            val mine = localByKey[key]
            val theirs = remoteByKey[key]
            when {
                mine != null && theirs != null -> {
                    val entry = mine.copy(chapters = mergeChapters(mine.chapters, theirs.chapters))
                    merged += entry
                    if (entry.chapters != mine.chapters) update += entry
                }
                mine != null -> if (key in known) remove += key else merged += mine
                theirs != null -> if (key !in known) {
                    merged += theirs
                    add += theirs
                }
            }
        }
        return SyncPlan(merged, add, update, remove)
    }

    private fun mergeChapters(
        mine: Map<String, SyncChapter>,
        theirs: Map<String, SyncChapter>,
    ): Map<String, SyncChapter> {
        return (mine.keys + theirs.keys).associateWith { url ->
            val a = mine[url] ?: SyncChapter()
            val b = theirs[url] ?: SyncChapter()
            SyncChapter(
                read = a.read || b.read,
                page = maxOf(a.page, b.page),
                bookmark = a.bookmark || b.bookmark,
            )
        }
    }
}
