package yomu.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import java.io.File

data class AppRelease(val versionCode: Long, val apkUrl: String, val size: Long)

/**
 * Keeps Yomu up to date from the releases of its GitHub repository, installing quietly while the app is
 * in the background so that an update never closes it during reading.
 */
@Inject
@SingleIn(AppScope::class)
class YomuUpdater(private val context: Context, private val network: NetworkHelper) {

    private var watching = false

    val installedVersion: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** The newest published build, or null when there's none newer than this one. */
    suspend fun newer(): AppRelease? = withContext(Dispatchers.IO) {
        val text = network.client.newCall(GET(RELEASES, HEADERS)).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body.string()
        }
        newestRelease(text, Build.SUPPORTED_ABIS.toList())?.takeIf { it.versionCode > installedVersion }
    }

    /** The apk on the device; a download that broke off is started again. */
    suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "yomu_update").apply { mkdirs() }
        val target = File(folder, "yomu-${release.versionCode}.apk")
        if (target.length() == release.size && release.size > 0) return@withContext target
        folder.listFiles()?.forEach(File::delete)
        val partial = File(folder, "download.part")
        network.client.newCall(GET(release.apkUrl)).await().use { response ->
            check(response.isSuccessful) { "Update download failed with ${response.code}" }
            partial.outputStream().use { response.body.byteStream().copyTo(it) }
        }
        check(partial.renameTo(target)) { "Couldn't keep the downloaded update" }
        target
    }

    fun isInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Since Android 12 an app may update itself without asking, once it installed the version being
     * replaced; the first time, or on older Android, the system shows its confirmation instead.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("yomu.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** An update found while reading waits for the moment the app is left. */
    fun watchBackground() {
        if (watching) return
        watching = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    UpdateWorker.runOnce(context)
                }
            },
        )
    }

    companion object {
        private const val REPOSITORY = "JakeKing0001/yomu"
        private const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=20"
        private const val TAG_PREFIX = "android-v"
        private val HEADERS = Headers.headersOf("Accept", "application/vnd.github+json", "User-Agent", "Yomu")

        /** The highest android-v<code> release with an apk for one of the device's ABIs. */
        fun newestRelease(text: String, abis: List<String>): AppRelease? {
            val releases = Json.parseToJsonElement(text) as? JsonArray ?: return null
            return releases.mapNotNull { element ->
                val release = element as? JsonObject ?: return@mapNotNull null
                if (release["draft"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
                val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val code = tag.removePrefix(TAG_PREFIX).takeIf { tag.startsWith(TAG_PREFIX) }?.toLongOrNull()
                    ?: return@mapNotNull null
                val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val asset = abis.firstNotNullOfOrNull { abi ->
                    assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("$abi.apk") == true }
                } ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("universal.apk") == true }
                    ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                AppRelease(code, url, asset["size"]?.jsonPrimitive?.longOrNull ?: 0)
            }.maxByOrNull { it.versionCode }
        }
    }
}
