package yomu.reader

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class YomuPreferences(preferenceStore: PreferenceStore) {

    init {
        // Earlier builds could store an Anthropic key here, and nothing uses it anymore.
        preferenceStore.getString(Preference.privateKey("yomu_ai_api_key"), "").delete()
    }

    val ambientGlass: Preference<Boolean> = preferenceStore.getBoolean("yomu_ambient_glass", true)

    val panelMode: Preference<Boolean> = preferenceStore.getBoolean("yomu_panel_mode", false)

    val translatePages: Preference<Boolean> = preferenceStore.getBoolean("yomu_translate_pages", false)

    val enhancePages: Preference<Boolean> = preferenceStore.getBoolean("yomu_enhance_pages", false)

    val aiEnhance: Preference<Boolean> = preferenceStore.getBoolean("yomu_ai_enhance", false)

    val autoScrollSpeed: Preference<Float> = preferenceStore.getFloat("yomu_auto_scroll_speed", 1f)

    /** Learned seconds an average page takes this reader. */
    val pageSeconds: Preference<Float> = preferenceStore.getFloat(
        Preference.appStateKey("yomu_page_seconds"),
        DEFAULT_PAGE_SECONDS,
    )

    val readingMemory: Preference<Boolean> = preferenceStore.getBoolean("yomu_reading_memory", false)

    val volumeView: Preference<Boolean> = preferenceStore.getBoolean("yomu_volume_view", false)

    val welcomeBack: Preference<Boolean> = preferenceStore.getBoolean("yomu_welcome_back", true)

    val voiceRate: Preference<Float> = preferenceStore.getFloat("yomu_voice_rate", 1f)

    val offlineReady: Preference<Boolean> = preferenceStore.getBoolean("yomu_offline_ready", false)

    val offlineChapters: Preference<Int> = preferenceStore.getInt("yomu_offline_chapters", 3)

    val syncFile: Preference<String> = preferenceStore.getString(Preference.appStateKey("yomu_sync_file"), "")

    val syncKnown: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("yomu_sync_known"),
        emptySet(),
    )

    val syncLastAt: Preference<Long> = preferenceStore.getLong(Preference.appStateKey("yomu_sync_last_at"), 0L)

    val searchPack: Preference<Boolean> = preferenceStore.getBoolean("yomu_search_pack", true)

    val sourceHealth: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("yomu_source_health"),
        emptySet(),
    )

    val repairTried: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("yomu_repair_tried"),
        emptySet(),
    )

    val repairReplaced: Preference<Int> = preferenceStore.getInt(Preference.appStateKey("yomu_repair_replaced"), 0)

    val repairCovers: Preference<Int> = preferenceStore.getInt(Preference.appStateKey("yomu_repair_covers"), 0)

    val packLanguagesShown: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("yomu_pack_languages_shown"),
        false,
    )

    val fillTried: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("yomu_fill_tried"),
        emptySet(),
    )

    val autoUpdate: Preference<Boolean> = preferenceStore.getBoolean("yomu_auto_update", true)

    /** The reader's own Google AI Studio key, kept out of backups. Empty uses the free model. */
    val geminiKey: Preference<String> = preferenceStore.getString(Preference.privateKey("yomu_gemini_key"), "")

    /** Android asked to confirm an update while Yomu was in the background. */
    val updateNeedsConfirm: Preference<Boolean> = preferenceStore.getBoolean("yomu_update_needs_confirm", false)

    val dismissedManga: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("yomu_dismissed_manga"),
        emptySet(),
    )

    val catalogIds: Preference<String> = preferenceStore.getString(Preference.appStateKey("yomu_catalog_ids"), "")

    val catalogUpdatedAt: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("yomu_catalog_updated_at"),
        0L,
    )

    companion object {
        const val DEFAULT_PAGE_SECONDS = 14f
    }
}
