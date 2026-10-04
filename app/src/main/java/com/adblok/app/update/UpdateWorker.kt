package com.adblok.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adblok.app.R
import com.adblok.app.data.Prefs
import com.adblok.app.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Периодически (раз в сутки, только по сети) проверяет новые релизы
 * и показывает уведомление с кнопкой установки.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.autoUpdate) return Result.success()

        val info = UpdateChecker.check() ?: return Result.success()
        prefs.lastUpdateCheck = System.currentTimeMillis()

        // Качаем заранее, чтобы по нажатию сразу открывался установщик.
        val apk = UpdateChecker.download(applicationContext, info) ?: return Result.retry()
        notify(info, apk.absolutePath)
        return Result.success()
    }

    private fun notify(info: UpdateInfo, apkPath: String) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Обновления ADBlok", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_INSTALL_UPDATE)
            .putExtra(MainActivity.EXTRA_APK_PATH, apkPath)
            .putExtra(MainActivity.EXTRA_BUILD, info.buildNumber)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, info.buildNumber, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        nm.notify(
            NOTIF_ID,
            builder
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(ctx.getString(R.string.update_available_title))
                .setContentText(ctx.getString(R.string.update_available_text, info.buildNumber))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "adblok_updates"
        private const val NOTIF_ID = 43
        private const val WORK_NAME = "adblok-update-check"

        /** Ставит (или снимает) суточную проверку обновлений. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
