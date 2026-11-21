package com.asrcodes.dontmissmom

import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokenSync {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    fun sync(token: String? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val version = ++generation
        fun attempt(remaining: Int) {
            if (version != generation || FirebaseAuth.getInstance().currentUser?.uid != uid) return
            fun retry() { if (remaining > 0) handler.postDelayed({ attempt(remaining - 1) }, 15_000L) }
            fun save(value: String) {
                if (version != generation || FirebaseAuth.getInstance().currentUser?.uid != uid) return
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .update(mapOf("fcmToken" to value, "fcmTokenUpdatedAt" to FieldValue.serverTimestamp()))
                    .addOnFailureListener { retry() }
            }
            if (token != null) save(token)
            else FirebaseMessaging.getInstance().token.addOnSuccessListener { save(it) }.addOnFailureListener { retry() }
        }
        attempt(3)
    }
}
