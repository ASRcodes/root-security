package com.asrcodes.dontmissmom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth

object AlertDelivery {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL = "emergency_alerts_v2"

    @Synchronized fun deliver(context: Context, alert: IncomingAlert) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (alert.recipientUid != uid) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) { AlertReceipt.update(alert, "received"); return }
        val prefs = context.getSharedPreferences("delivered_alerts", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (alert.id.isNotBlank() && now - prefs.getLong(alert.id, 0) < 5 * 60_000L) return
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) -> if (value is Long && now - value > 10 * 60_000L) editor.remove(key) }
        if (alert.id.isNotBlank()) editor.putLong(alert.id, now)
        editor.apply()
        AlertReceipt.update(alert, "received")
        manager.notify(NOTIFICATION_ID, notification(context, alert))
        try {
            ContextCompat.startForegroundService(context, Intent(context, EmergencySoundService::class.java).apply {
                action = EmergencySoundService.ACTION_START
                putExtras(alert.intent(context))
            })
        } catch (_: IllegalStateException) {
            // Background-start restrictions: the notification remains actionable.
        } catch (_: SecurityException) { }
    }

    fun notification(context: Context, alert: IncomingAlert): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val soundEnabled = SirenSettings(context).enabled()
        val channelId = if (soundEnabled) CHANNEL else "emergency_vibration_v2"
        val channel = NotificationChannel(channelId, context.getString(R.string.siren_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
            if (manager.isNotificationPolicyAccessGranted) setBypassDnd(true)
            setSound(if (soundEnabled) Uri.parse("android.resource://" + context.packageName + "/" + R.raw.siren) else null,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 1001, alert.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, 1002, Intent(context, StopSirenReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = alert.name.ifBlank { context.getString(R.string.unknown_sender) }
        return NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.emergency_label)).setContentText(context.getString(R.string.emergency_from, name))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open).setFullScreenIntent(open, true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDeleteIntent(stop).addAction(R.drawable.ic_close, context.getString(R.string.stop_siren), stop)
            .setAutoCancel(false).setOnlyAlertOnce(true).build()
    }

    fun dismiss(context: Context) {
        context.stopService(Intent(context, EmergencySoundService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        context.getSystemService(NotificationManager::class.java).cancel(999)
    }

    fun showHistoryNotification(context: Context, alert: IncomingAlert) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("emergency_history", context.getString(R.string.history), NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, MainActivity::class.java).putExtra("openHistory", true).putExtra("toUid", alert.recipientUid)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 1003, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, "emergency_history").setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.missed_emergency)).setContentText(context.getString(R.string.history_notification_body, alert.name))
            .setContentIntent(open).setAutoCancel(true).setSilent(true).build())
    }
}
