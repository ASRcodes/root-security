package com.asrcodes.dontmissmom

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

data class EmergencyHistory(
    val id: String, val alertId: String, val senderUid: String, val name: String, val phone: String,
    val latitude: Double?, val longitude: Double?, val voiceUrl: String?, val timestamp: Long,
    val deliveryStatus: String = "accepted", val sirenStatus: String = "", val receivedAt: Long = 0,
    val direction: String = "received", val voiceExpired: Boolean = false
) {
    companion object {
        fun from(doc: DocumentSnapshot): EmergencyHistory {
            val lat = doc.getDouble("latitude"); val lng = doc.getDouble("longitude")
            val valid = IncomingAlert.validCoordinates(lat, lng)
            return EmergencyHistory(doc.id, doc.getString("alertId").orEmpty(), doc.getString("counterpartUid").orEmpty(),
                doc.getString("counterpartName").orEmpty(), doc.getString("counterpartPhone").orEmpty(),
                if (valid) lat else null, if (valid) lng else null,
                doc.getString("voiceUrl")?.takeIf(IncomingAlert::validVoiceUrl), millis(doc.get("timestamp")),
                doc.getString("deliveryStatus") ?: "accepted", doc.getString("sirenStatus").orEmpty(), millis(doc.get("receivedAt")),
                doc.getString("direction") ?: "received", doc.getBoolean("voiceExpired") == true)
        }
        private fun millis(value: Any?): Long = when (value) { is Timestamp -> value.toDate().time; is Number -> value.toLong(); else -> 0 }
    }
}

/** Device receipt is independent of the lifetime of the siren and of opening the popup. */
object AlertReceipt {
    fun update(alert: IncomingAlert, state: String) {
        if (alert.id.isBlank() || alert.recipientUid != FirebaseAuth.getInstance().currentUser?.uid) return
        val values = mutableMapOf<String, Any>("sirenStatus" to state)
        if (state == "ringing" || state == "received") values["receivedAt"] = FieldValue.serverTimestamp()
        else values["sirenStoppedAt"] = FieldValue.serverTimestamp()
        FirebaseFirestore.getInstance().collection("history").document(alert.id + "_received").update(values)
    }
}
