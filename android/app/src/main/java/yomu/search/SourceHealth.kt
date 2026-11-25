package yomu.search

import tachiyomi.core.common.preference.Preference

/**
 * Sources that keep failing, a site gone or blocking, are skipped for a day instead of slowing
 * every search down to their timeout.
 */
class SourceHealth(
    private val store: Preference<Set<String>>,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class Record(val failures: Int, val lastFailure: Long)

    private val records = store.get().mapNotNull { line ->
        val parts = line.split(':')
        val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val failures = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val last = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
        id to Record(failures, last)
    }.toMap(java.util.concurrent.ConcurrentHashMap())

    fun isUsable(sourceId: Long): Boolean {
        val record = records[sourceId] ?: return true
        return record.failures < SKIP_AFTER || now() - record.lastFailure > RETRY_AFTER_MS
    }

    fun failed(sourceId: Long) {
        records.compute(sourceId) { _, old -> Record((old?.failures ?: 0) + 1, now()) }
        save()
    }

    fun worked(sourceId: Long) {
        if (records.remove(sourceId) != null) save()
    }

    private fun save() {
        store.set(records.mapTo(HashSet()) { (id, record) -> "$id:${record.failures}:${record.lastFailure}" })
    }

    private companion object {
        const val SKIP_AFTER = 2
        const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
