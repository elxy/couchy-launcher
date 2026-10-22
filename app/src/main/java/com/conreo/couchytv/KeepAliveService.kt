package com.conreo.couchytv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the launcher's process out of the "cached" bucket.
 *
 * Why this exists: the TV has 1.7 GB of RAM and kills the launcher's process
 * while another app (a video app, typically) is in the foreground. Pressing
 * Home then costs a full cold start — the visible symptom is a dark screen for
 * a few seconds. A foreground service pins the process at a low `oom_adj`, so
 * returning to the launcher is just `ON_RESUME` (measured: 1 ms to render).
 *
 * This is the user's explicit choice on this device. It costs a small
 * permanent notification and ~90 MB of resident memory, and it is the only
 * route that works without root: `oom_score_adj` cannot be written by a normal
 * app, vendor quick-mode lists are a no-op stub in this firmware, and
 * Doze/standby-bucket exemptions do not affect cached-process reclaim.
 *
 * Started and stopped from [LauncherSettings.keepResident] so the feature is
 * visible and reversible; nothing starts it behind the user's back.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system does reclaim us anyway (low memory), it
        // restarts the service as soon as it can, which re-pins the process.
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // Silent and unobtrusive: this is a memory hint, not an event.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(getString(R.string.keep_alive_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // targetSdk 34 requires an explicit foreground-service type. A
            // launcher keeping itself warm is `specialUse`; this device is API
            // 28, where the type is ignored entirely.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_MIN: no sound, no peek, collapsed to the bottom of any list.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = getString(R.string.keep_alive_text)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        /** Start pinning the process, or stop and drop the notification. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (enabled) {
                // startForegroundService on O+ so the service gets its 5s window;
                // below that a plain startService is enough.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
