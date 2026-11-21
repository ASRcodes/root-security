package com.asrcodes.dontmissmom

import android.content.Context
import android.content.Intent
import java.net.URI

data class IncomingAlert(val id: String, val name: String, val phone: String,
    val latitude: Double?, val longitude: Double?, val voiceUrl: String?, val recipientUid: String? = null) {
    fun intent(context: Context) = Intent(context, AlertPopupActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("alertId", id); putExtra("senderName", name); putExtra("senderPhone", phone)
        latitude?.let { putExtra("latitude", it) }; longitude?.let { putExtra("longitude", it) }
        voiceUrl?.let { putExtra("voiceUrl", it) }; recipientUid?.let { putExtra("toUid", it) }
    }

    companion object {
        fun fromData(data: Map<String, String>, fallbackId: String): IncomingAlert {
            val lat = data["latitude"]?.toDoubleOrNull()
            val lng = data["longitude"]?.toDoubleOrNull()
            val valid = validCoordinates(lat, lng) && data["hasLocation"] != "false" && !(lat == 0.0 && lng == 0.0 && data["hasLocation"] != "true")
            return IncomingAlert(data["alertId"] ?: fallbackId,
                (data["fromName"] ?: data["senderName"]).orEmpty().take(120),
                (data["fromPhone"] ?: data["senderPhone"]).orEmpty().take(32),
                if (valid) lat else null, if (valid) lng else null,
                data["voiceUrl"]?.takeIf(::validVoiceUrl), data["toUid"])
        }
        fun fromIntent(intent: Intent): IncomingAlert = IncomingAlert(
            intent.getStringExtra("alertId") ?: "", intent.getStringExtra("senderName").orEmpty(),
            intent.getStringExtra("senderPhone").orEmpty(),
            if (intent.hasExtra("latitude")) intent.getDoubleExtra("latitude", 0.0) else null,
            if (intent.hasExtra("longitude")) intent.getDoubleExtra("longitude", 0.0) else null,
            intent.getStringExtra("voiceUrl")?.takeIf(::validVoiceUrl), intent.getStringExtra("toUid"))
        fun validCoordinates(lat: Double?, lng: Double?) = lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
        fun validVoiceUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "res.cloudinary.com" && uri.userInfo == null &&
                (uri.path.startsWith("/dqrb7jasx/raw/upload/") || uri.path.startsWith("/dqrb7jasx/video/upload/"))
        }.getOrDefault(false)
    }
}
