package com.groq.voicetyper.quickcontrols

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.groq.voicetyper.R

/**
 * Quiet, swipe-dismissible reminder posted only when the user explicitly pauses
 * the Floating Bubble for payments. Auto-cancels the instant the Accessibility
 * Service reconnects so users never forget to turn it back on.
 */
object PaymentPauseNotifier {
    private const val TAG = "PaymentPauseNotifier"
    private const val CHANNEL_ID = "fluence_payment_pause"
    private const val CHANNEL_NAME = "Fluence Bubble Status"
    private const val NOTIFICATION_ID = 2027

    fun showPausedReminder(context: Context) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Reminder to re-enable Fluence Floating Bubble after payments"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }

            val settingsIntent = FluenceQuickControls.createAccessibilitySettingsIntent(appCtx)
            val pendingIntent = PendingIntent.getActivity(
                appCtx,
                NOTIFICATION_ID,
                settingsIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_bubble)
                .setContentTitle("Fluence Bubble paused for payments")
                .setContentText("Tap to turn it back on when done. Voice Keyboard still works.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Tap to open Accessibility Settings and turn Fluence back on when done. Voice Keyboard still works."
                    )
                )
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post payment-pause reminder notification", e)
        }
    }

    fun dismiss(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss payment-pause reminder notification", e)
        }
    }
}
