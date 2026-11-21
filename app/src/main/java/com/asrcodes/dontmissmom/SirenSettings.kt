package com.asrcodes.dontmissmom

import android.content.Context

class SirenSettings(context: Context) {
    private val prefs = context.getSharedPreferences("DontMissMomPrefs", Context.MODE_PRIVATE)
    fun enabled(now: Long = System.currentTimeMillis()): Boolean {
        val enabled = SirenPolicy.isEnabled(prefs.getBoolean("siren_enabled", true),
            prefs.getBoolean("siren_permanent_off", false), prefs.getLong("siren_off_timestamp", 0), now)
        if (enabled && !prefs.getBoolean("siren_enabled", true)) enable()
        return enabled
    }
    fun permanent() = prefs.getBoolean("siren_permanent_off", false)
    fun enable() { prefs.edit().putBoolean("siren_enabled", true).putBoolean("siren_permanent_off", false).putLong("siren_off_timestamp", 0).apply() }
    fun mute(permanent: Boolean) { prefs.edit().putBoolean("siren_enabled", false).putBoolean("siren_permanent_off", permanent).putLong("siren_off_timestamp", System.currentTimeMillis()).apply() }
}
