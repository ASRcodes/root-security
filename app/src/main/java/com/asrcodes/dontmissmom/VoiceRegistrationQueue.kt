package com.asrcodes.dontmissmom

import android.content.Context
import com.google.firebase.auth.FirebaseAuth

/** Retry cleanup registration after a cold backend/network failure, without delaying SOS. */
object VoiceRegistrationQueue {
    private val inFlight = mutableSetOf<String>()
    fun add(context: Context, url: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = context.getSharedPreferences("voice_registrations", Context.MODE_PRIVATE)
        prefs.edit().putStringSet(uid, prefs.getStringSet(uid, emptySet()).orEmpty() + url).apply()
        sync(context)
    }
    fun sync(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = context.applicationContext.getSharedPreferences("voice_registrations", Context.MODE_PRIVATE)
        prefs.getStringSet(uid, emptySet()).orEmpty().toList().forEach { url ->
            val key = uid + ":" + url
            if (inFlight.add(key)) FirebaseAppRepository().registerVoice(url) { result ->
                inFlight.remove(key)
                if (result.isSuccess) prefs.edit().putStringSet(uid, prefs.getStringSet(uid, emptySet()).orEmpty() - url).apply()
            }
        }
    }
}
