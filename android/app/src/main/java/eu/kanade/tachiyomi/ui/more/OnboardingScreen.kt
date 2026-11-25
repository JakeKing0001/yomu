package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.more.onboarding.OnboardingScreen
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import mihon.app.di.appGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.LocalNavigator
import mihon.presentation.core.util.isTabletUi
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val isTabletUi = isTabletUi()

    val basePreferences = remember { context.appGraph.basePreferences }
    val shownOnboardingFlow by basePreferences.shownOnboardingFlow.collectAsState()
    val setup = remember { context.appGraph.defaultSourceSetup }
    val state by setup.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { setup.start() }

    val finishOnboarding: () -> Unit = {
        basePreferences.shownOnboardingFlow.set(true)
        navigator.pop()
    }

    val restoreSettingKey = stringResource(SettingsDataScreen.restorePreferenceKeyString)

    BackHandler(enabled = !shownOnboardingFlow) {
        // Prevent exiting if onboarding hasn't been completed
    }

    OnboardingScreen(
        state = state,
        onStart = {
            finishOnboarding()
            state.sourceId?.let { navigator.push(BrowseSourceRoute(it, GetRemoteManga.QUERY_POPULAR)) }
        },
        onRetry = setup::start,
        onContinue = finishOnboarding,
        onRestoreBackup = {
            finishOnboarding()
            SearchableSettings.highlightKey = restoreSettingKey
            navigator.openSettings(SettingsDataScreen, isTabletUi)
        },
    )
}
