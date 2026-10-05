package com.androidcamera.webcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.androidcamera.webcam.R
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.locale.LocaleManager
import com.androidcamera.webcam.ui.MainActivity

/**
 * Creates and updates the foreground notification for the streaming service.
 * Strings are loaded from the XML resource set matching the selected language.
 */
class StreamNotificationFactory(private val appContext: Context) {

    private val localizedContext: Context
        get() {
            val prefs = PreferencesRepository(appContext)
            return LocaleManager(prefs).wrapWithSavedLocale(appContext)
        }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = localizedContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(cameraLabel: String, isActive: Boolean): Notification {
        ensureChannel()
        val context = localizedContext

        val openIntent = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            appContext,
            1,
            Intent(appContext, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = context.getString(R.string.notification_text_single, cameraLabel)

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                context.getString(R.string.notification_action_stop),
                stopIntent
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "webcam_stream_channel"
        const val NOTIFICATION_ID = 1001
    }
}
