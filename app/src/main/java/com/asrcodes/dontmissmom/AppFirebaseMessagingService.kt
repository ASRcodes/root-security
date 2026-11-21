package com.asrcodes.dontmissmom

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AppFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return
        if (data["type"] == RequestNotifications.TYPE) { RequestNotifications.show(this, data); return }
        if (data["type"] != null && data["type"] != "EMERGENCY_CALL") return
        if (data["fromName"] == null && data["senderName"] == null) return
        val sentAt = data["sentAt"]?.toLongOrNull()
        if (sentAt != null && System.currentTimeMillis() - sentAt > 5 * 60_000L) return
        AlertDelivery.deliver(this, IncomingAlert.fromData(data, message.messageId ?: ""))
    }
    override fun onNewToken(token: String) {
        FcmTokenSync.sync(token)
    }
}
