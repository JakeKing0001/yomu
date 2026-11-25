package yomu.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/** Hears how an update install went; when Android wants a confirmation, the reader gets a notification. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context.metroGraph<AppGraph>().yomuPreferences.updateNeedsConfirm.set(false)
                context.notify(NOTIFICATION_ID, Notifications.CHANNEL_COMMON) {
                    setSmallIcon(R.drawable.ic_mihon)
                    setContentTitle(context.stringResource(MR.strings.yomu_update_done))
                    setAutoCancel(true)
                }
            }
            else -> onStatus(context, intent)
        }
    }

    private fun onStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (status != PackageInstaller.STATUS_SUCCESS) {
                logcat(LogPriority.WARN) { "Update install ended with $status" }
            }
            return
        }
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // With Yomu open the system confirmation shows right away; otherwise it waits for the next launch,
        // since a notification goes unseen when notifications are off.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            context.startActivity(confirm)
            return
        }
        context.metroGraph<AppGraph>().yomuPreferences.updateNeedsConfirm.set(true)
        context.notify(NOTIFICATION_ID, Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.yomu_update_ready))
            setContentText(context.stringResource(MR.strings.yomu_update_ready_summary))
            setAutoCancel(true)
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    private companion object {
        const val NOTIFICATION_ID = -8_101
    }
}
