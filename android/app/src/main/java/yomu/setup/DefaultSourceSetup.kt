package yomu.setup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.seconds

enum class SetupStep { Working, Done, Failed }

data class DefaultSourceState(
    val store: SetupStep = SetupStep.Working,
    val source: SetupStep = SetupStep.Working,
    val sourceId: Long? = null,
) {
    val isReady: Boolean get() = store == SetupStep.Done && source == SetupStep.Done
    val hasFailed: Boolean get() = store == SetupStep.Failed || source == SetupStep.Failed
}

/** Adds the Keiyoushi store and installs Mangaworld once, so a removal by the user is never undone. */
@Inject
@SingleIn(AppScope::class)
class DefaultSourceSetup(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val extensionManager: ExtensionManager,
    private val extensionStoreRepository: ExtensionStoreRepository,
    private val addExtensionStore: AddExtensionStore,
    private val sourcePreferences: SourcePreferences,
    preferenceStore: PreferenceStore,
) {

    private val completed = preferenceStore.getBoolean(Preference.appStateKey("default_source_ready"), false)

    private val _state = MutableStateFlow(
        if (completed.get()) {
            DefaultSourceState(store = SetupStep.Done, source = SetupStep.Done)
        } else {
            DefaultSourceState()
        },
    )
    val state: StateFlow<DefaultSourceState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (completed.get() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { configure() }
    }

    private suspend fun configure() {
        _state.value = DefaultSourceState()
        try {
            if (extensionStoreRepository.getAll().none { it.indexUrl == KEIYOUSHI_INDEX_URL }) {
                addExtensionStore(KEIYOUSHI_INDEX_URL).getOrThrow()
            }
            _state.update { it.copy(store = SetupStep.Done) }

            val extension = findMangaworld()
            ensureInstalled(extension)
            val source = extension.sources.first(Extension.Available.Source::isMangaworld)

            sourcePreferences.enabledLanguages.getAndSet { it + source.lang }
            sourcePreferences.pinnedSources.getAndSet { it + source.id.toString() }
            completed.set(true)
            _state.update { it.copy(source = SetupStep.Done, sourceId = source.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to set up the default source" }
            _state.update {
                if (it.store == SetupStep.Done) {
                    it.copy(source = SetupStep.Failed)
                } else {
                    it.copy(store = SetupStep.Failed, source = SetupStep.Failed)
                }
            }
        }
    }

    private suspend fun findMangaworld(): Extension.Available {
        extensionManager.findAvailableExtensions()
        return withTimeoutOrNull(STEP_TIMEOUT) {
            extensionManager.availableExtensionsFlow
                .first { extensions -> extensions.any { it.sources.any(Extension.Available.Source::isMangaworld) } }
                .first { it.sources.any(Extension.Available.Source::isMangaworld) }
        } ?: error("Mangaworld isn't listed in the store")
    }

    private suspend fun ensureInstalled(extension: Extension.Available) {
        if (extensionManager.getLoadedExtensions().any { it.pkgName == extension.pkgName }) return

        val result = extensionManager.installExtension(extension).first(InstallStep::isCompleted)
        check(result == InstallStep.Installed) { "Mangaworld install ended with $result" }

        // The source can only be opened once the install receiver has loaded it
        withTimeoutOrNull(STEP_TIMEOUT) {
            extensionManager.loadedExtensionsFlow.first { loaded -> loaded.any { it.pkgName == extension.pkgName } }
        } ?: error("Mangaworld didn't load after installing")
    }
}

private fun Extension.Available.Source.isMangaworld(): Boolean {
    return lang == "it" && name.equals("Mangaworld", ignoreCase = true)
}

private const val KEIYOUSHI_INDEX_URL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
private val STEP_TIMEOUT = 20.seconds
