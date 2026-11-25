package yomu.search

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extension.model.ContentWarning
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import yomu.reader.YomuPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds

@Serializable
data class PackSource(val id: Long, val lang: String, val name: String)

@Serializable
data class PackEntry(
    val pkgName: String,
    val name: String,
    val versionCode: Long,
    val sources: List<PackSource>,
    val bytes: Long,
    val warning: String = "",
) {
    /** Kept out of searches; series of mixed sites are checked one by one by their genres instead. */
    val isAdult: Boolean get() = warning == ContentWarning.NSFW.name
}

data class PackStatus(val ready: Int = 0, val bytes: Long = 0, val isUpdating: Boolean = false, val wanted: Int = 0)

/**
 * Every safe extension of the catalogue, kept on disk for searching only.
 *
 * They live apart from the installed extensions, so the app never loads them at launch: a search
 * loads the ones it needs, and an extension joins the installed ones when a result from it is opened.
 */
@Inject
@SingleIn(AppScope::class)
class SearchPack(
    private val context: Context,
    private val network: NetworkHelper,
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val preferences: YomuPreferences,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val folder get() = File(context.filesDir, "yomu_search_exts").apply { mkdirs() }
    private val manifestFile get() = File(folder, "manifest.json")
    private val loaded = ConcurrentHashMap<String, List<CatalogueSource>>()

    private val _status = MutableStateFlow(PackStatus())
    val status: StateFlow<PackStatus> = _status.asStateFlow()

    @Volatile
    private var manifest: Map<String, PackEntry>? = null

    fun entries(): List<PackEntry> = manifestMap().values.toList()

    fun owner(sourceId: Long): PackEntry? = manifestMap().values.firstOrNull { entry ->
        entry.sources.any {
            it.id ==
                sourceId
        }
    }

    /** Brings the pack in line with the catalogue: new and updated extensions in, removed ones out. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.update { it.copy(isUpdating = true) }
            try {
                extensionManager.findAvailableExtensions()
                val available = withTimeoutOrNull(LIST_TIMEOUT) {
                    extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
                } ?: return@withLock
                val installed = (extensionManager.getLoadedExtensions() + extensionManager.getNotLoadedExtensions())
                    .mapTo(HashSet()) { it.pkgName }
                val allowed = sourcePreferences.enabledContentWarnings.get()
                val wanted = available
                    .filter { isWanted(it.contentWarning, it.sources.map(Extension.Available.Source::lang), allowed) }
                    .filter { it.pkgName !in installed }
                    .groupBy { it.pkgName }
                    .map { (_, versions) -> versions.maxBy { it.versionCode } }
                _status.update { it.copy(wanted = wanted.size) }

                val current = manifestMap().toMutableMap()
                val wantedNames = wanted.mapTo(HashSet()) { it.pkgName }
                current.keys.filter { it !in wantedNames }.forEach { forget(current, it) }

                // Listings change their warning without a new version, so it's refreshed for every entry.
                wanted.forEach { extension ->
                    current[extension.pkgName]?.takeIf { it.warning != extension.contentWarning.name }?.let {
                        current[extension.pkgName] = it.copy(warning = extension.contentWarning.name)
                    }
                }
                val missing = wanted.filter { current[it.pkgName]?.versionCode != it.versionCode }
                val gate = Semaphore(PARALLEL_DOWNLOADS)
                coroutineScope {
                    missing.map { extension ->
                        async {
                            gate.withPermit {
                                download(extension)?.let { entry ->
                                    synchronized(current) { current[entry.pkgName] = entry }
                                    publish(current)
                                }
                            }
                        }
                    }.awaitAll()
                }
                publish(current)
            } finally {
                _status.update { it.copy(isUpdating = false) }
            }
        }
    }

    /** Loads the given extensions, once per session. */
    suspend fun load(entries: List<PackEntry>): List<CatalogueSource> = withContext(Dispatchers.IO) {
        val missing = entries.filter { !loaded.containsKey(it.pkgName) }
        if (missing.isNotEmpty()) {
            val files = missing.map { File(folder, "${it.pkgName}.$EXTENSION_SUFFIX") }.filter(File::isFile)
            ExtensionLoader.loadExtensionFiles(context, files).forEach { extension ->
                loaded[extension.pkgName] = extension.sources.filterIsInstance<CatalogueSource>()
            }
            missing.forEach { loaded.putIfAbsent(it.pkgName, emptyList()) }
        }
        entries.flatMap { loaded[it.pkgName].orEmpty() }
    }

    /** Lets the loaded extensions go once nothing is searching, since only a search needs them. */
    fun release() {
        loaded.clear()
    }

    fun includes(pkgName: String): Boolean = packageOf(pkgName) in manifestMap()

    /** Installs a whole extension from the pack, instantly since it's already on the device. */
    suspend fun installPackage(pkgName: String): Boolean = withContext(Dispatchers.IO) {
        val entry = manifestMap()[packageOf(pkgName)] ?: return@withContext false
        val sourceId = entry.sources.firstOrNull()?.id ?: return@withContext false
        install(sourceId) != null
    }

    /** Installs the extension behind [sourceId] for good, so its series can be opened and read. */
    suspend fun install(sourceId: Long): Source? = withContext(Dispatchers.IO) {
        sourceManager.get(sourceId)?.let { return@withContext it }
        val entry = owner(sourceId) ?: return@withContext null
        val file = File(folder, "${entry.pkgName}.$EXTENSION_SUFFIX")
        val info = ExtensionLoader.getArchivePackageInfo(context, file) ?: return@withContext null
        ExtensionLoader.installPrivateExtensionFile(context, file, info)
        val source = withTimeoutOrNull(INSTALL_TIMEOUT) {
            var found: Source? = null
            while (found == null) {
                delay(POLL_MS)
                found = sourceManager.get(sourceId)
            }
            found
        } ?: return@withContext null
        sourcePreferences.enabledLanguages.getAndSet { it + source.lang }
        mutex.withLock {
            val current = manifestMap().toMutableMap()
            forget(current, entry.pkgName)
            publish(current)
        }
        source
    }

    private suspend fun download(extension: Extension.Available): PackEntry? {
        val temporary = File(context.cacheDir, "pack_${extension.pkgName}.apk")
        return try {
            network.client.newCall(GET(extension.apkUrl)).await().use { response ->
                if (!response.isSuccessful) return null
                temporary.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            // Same rule as a normal install: the apk must carry the key of the store that lists it.
            val info = ExtensionLoader.getArchivePackageInfo(context, temporary)
                ?.takeIf { extension.store.signingKey in ExtensionLoader.getSignatures(it).orEmpty() }
                ?: return null
            if (info.packageName != extension.pkgName) return null
            if (needsUserInteraction(temporary)) return null
            val target = File(folder, "${extension.pkgName}.$EXTENSION_SUFFIX")
            target.delete()
            temporary.copyTo(target)
            // Android 14 refuses to load code from a writable file.
            target.setReadOnly()
            PackEntry(
                pkgName = extension.pkgName,
                name = extension.name,
                versionCode = extension.versionCode,
                sources = extension.sources.map { PackSource(it.id, it.lang, it.name) },
                bytes = target.length(),
                warning = extension.contentWarning.name,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Couldn't add ${extension.pkgName} to the search sources" }
            null
        } finally {
            temporary.delete()
        }
    }

    /** The extension list tells listings of different stores apart with a suffix that the package itself lacks. */
    private fun packageOf(listing: String) = listing.substringBefore('-')

    private fun forget(current: MutableMap<String, PackEntry>, pkgName: String) {
        File(folder, "$pkgName.$EXTENSION_SUFFIX").delete()
        current.remove(pkgName)
        loaded.remove(pkgName)
    }

    private fun publish(current: Map<String, PackEntry>) {
        val snapshot = synchronized(current) { current.toMap() }
        manifest = snapshot
        showLanguages(snapshot.values)
        val partial = File(folder, "manifest.part")
        partial.writeText(json.encodeToString(snapshot.values.toList()))
        partial.renameTo(manifestFile)
        _status.update { it.copy(ready = snapshot.size, bytes = snapshot.values.sumOf(PackEntry::bytes)) }
    }

    /** Once, so every source appears in the list; a language the reader turns off afterwards stays off. */
    private fun showLanguages(entries: Collection<PackEntry>) {
        if (entries.isEmpty() || preferences.packLanguagesShown.get()) return
        val languages = entries.flatMap { entry -> entry.sources.map(PackSource::lang) }.toSet()
        sourcePreferences.enabledLanguages.getAndSet { it + languages }
        preferences.packLanguagesShown.set(true)
    }

    private fun manifestMap(): Map<String, PackEntry> {
        manifest?.let { return it }
        val read = runCatching { json.decodeFromString<List<PackEntry>>(manifestFile.readText()) }
            .getOrDefault(emptyList())
            .filter { File(folder, "${it.pkgName}.$EXTENSION_SUFFIX").isFile }
            .associateBy { it.pkgName }
        manifest = read
        showLanguages(read.values)
        _status.update { it.copy(ready = read.size, bytes = read.values.sumOf(PackEntry::bytes)) }
        return read
    }

    companion object {
        private const val EXTENSION_SUFFIX = "ext"
        private const val PARALLEL_DOWNLOADS = 4
        private const val POLL_MS = 300L
        private val LIST_TIMEOUT = 30.seconds
        private val INSTALL_TIMEOUT = 20.seconds

        /**
         * Extensions that put a web page in a dialog ask the reader to solve a captcha, which would pop up
         * in the middle of a search across hundreds of sources.
         */
        fun needsUserInteraction(apk: File): Boolean {
            return ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .any { entry -> showsDialogWithWebView(zip.getInputStream(entry).use { it.readBytes() }) }
            }
        }

        fun showsDialogWithWebView(dex: ByteArray): Boolean {
            return dex.contains(DIALOG) && dex.contains(WEB_VIEW)
        }

        private val DIALOG = "Landroid/app/Dialog;".encodeToByteArray()
        private val WEB_VIEW = "Landroid/webkit/WebView;".encodeToByteArray()

        private fun ByteArray.contains(part: ByteArray): Boolean {
            if (part.isEmpty() || part.size > size) return false
            outer@ for (start in 0..size - part.size) {
                for (offset in part.indices) if (this[start + offset] != part[offset]) continue@outer
                return true
            }
            return false
        }

        /**
         * Follows the app's content setting, like the extension list does, but never takes adult ones.
         * Mixed multi-language extensions are the big catalogues such as MangaDex, so they always come.
         */
        fun isWanted(warning: ContentWarning, languages: List<String>, allowed: Set<ContentWarning>): Boolean {
            if (warning == ContentWarning.NSFW) return false
            return warning in allowed || (warning == ContentWarning.MIXED && languages.distinct().size > 1)
        }
    }
}
