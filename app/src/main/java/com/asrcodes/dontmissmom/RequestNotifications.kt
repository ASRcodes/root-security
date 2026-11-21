package com.asrcodes.dontmissmom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth

object RequestNotifications {
    const val TYPE = "TRUSTED_CONTACT_REQUEST"
    const val ACTION = "com.asrcodes.dontmissmom.OPEN_REQUESTS"
    const val CHANNEL = "trusted_contact_requests"
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.request_channel), NotificationManager.IMPORTANCE_HIGH))
    }
    fun show(context: Context, data: Map<String, String>) {
        if (data["toUid"] != FirebaseAuth.getInstance().currentUser?.uid) return
        val id = data["requestId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,300}")) } ?: return
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.parse("dontmissmom://request/" + id)).putExtra("requestId", id)
            .putExtra("toUid", data["toUid"]).putExtra("type", TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = context.getString(R.string.request_notification_body, data["fromName"].orEmpty().ifBlank { context.getString(R.string.unknown_sender) })
        manager.notify(id, 0, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.request_notification_title)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_SOCIAL).build())
    }
}
