package eu.kanade.presentation.more.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Error
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import yomu.presentation.glass.groupedCard
import yomu.setup.DefaultSourceState
import yomu.setup.SetupStep

@Composable
fun OnboardingScreen(
    state: DefaultSourceState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .systemBarsPadding()
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "読",
                        color = colors.onPrimary,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(MR.strings.onboarding_heading),
                    color = colors.onBackground,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (state.hasFailed) MR.strings.yomu_setup_failed else MR.strings.yomu_setup_description,
                    ),
                    color = colors.onBackground.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(28.dp))

            val showNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            SetupRow(
                title = "Keiyoushi",
                subtitle = stringResource(MR.strings.yomu_setup_store),
                first = true,
                last = false,
            ) { StepIndicator(state.store) }
            SetupRow(
                title = "Mangaworld",
                subtitle = stringResource(MR.strings.yomu_setup_source),
                first = false,
                last = !showNotifications,
            ) { StepIndicator(state.source) }
            if (showNotifications) NotificationsRow()
        }

        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = if (state.hasFailed) onRetry else onStart,
                enabled = state.isReady || state.hasFailed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.surfaceContainerHighest,
                    disabledContentColor = colors.onSurface,
                ),
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.hasFailed -> MR.strings.yomu_setup_retry
                            state.isReady -> MR.strings.yomu_setup_start
                            else -> MR.strings.yomu_setup_preparing
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            TextButton(
                onClick = if (state.hasFailed) onContinue else onRestoreBackup,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.hasFailed) MR.strings.yomu_setup_continue else MR.strings.yomu_setup_backup,
                    ),
                    color = colors.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun NotificationsRow() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requester = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    SetupRow(
        title = stringResource(MR.strings.yomu_setup_notifications),
        subtitle = stringResource(MR.strings.yomu_setup_notifications_description),
        first = false,
        last = true,
    ) {
        if (granted) {
            StepIndicator(SetupStep.Done)
        } else {
            TextButton(onClick = { requester.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(
                    text = stringResource(MR.strings.yomu_setup_enable),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    first: Boolean,
    last: Boolean,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .groupedCard(first = first, last = last)
            .height(68.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                color = colors.onSurface.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        trailing()
    }
}

@Composable
private fun StepIndicator(step: SetupStep) {
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        when (step) {
            SetupStep.Working -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
            )
            SetupStep.Done -> Icon(
                imageVector = MaterialSymbols.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            SetupStep.Failed -> Icon(
                imageVector = MaterialSymbols.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
