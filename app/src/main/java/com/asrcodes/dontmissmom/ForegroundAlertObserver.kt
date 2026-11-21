package com.asrcodes.dontmissmom

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore

/** Firestore fallback while the UI is active. FCM handles background delivery. */
class ForegroundAlertObserver(context: Context, uid: String) {
    private val app = context.applicationContext
    private val subscription = FirebaseFirestore.getInstance().collection("alerts").whereEqualTo("toUid", uid)
        .addSnapshotListener { snapshot, _ ->
            snapshot?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }?.forEach { change ->
                val doc = change.document
                val created = when (val value = doc.get("createdAt") ?: doc.get("timestamp")) {
                    is Timestamp -> value.toDate().time
                    is Number -> value.toLong()
                    else -> 0L
                }
                if (doc.getBoolean("handled") != true && System.currentTimeMillis() - created in 0..120_000L) {
                    val fromUid = doc.getString("fromUid")
                    if (!fromUid.isNullOrBlank() && fromUid != uid) {
                        FirebaseFirestore.getInstance().collection("users").document(uid)
                            .collection("trustedContacts").document(fromUid).get().addOnSuccessListener { contact ->
                                if (contact.exists() && contact.getBoolean("blocked") != true) {
                                    val data = doc.data.mapValues { it.value.toString() } + mapOf("alertId" to doc.id,
                                        "fromName" to contact.getString("username").orEmpty(), "fromPhone" to contact.getString("phone").orEmpty())
                                    AlertDelivery.deliver(app, IncomingAlert.fromData(data, doc.id))
                                    doc.reference.update("handled", true)
                                }
                            }
                    }
                }
            }
        }
    fun remove() { subscription.remove() }
}
