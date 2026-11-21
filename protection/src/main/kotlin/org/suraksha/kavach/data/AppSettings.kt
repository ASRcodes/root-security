package org.suraksha.kavach.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import org.suraksha.core.CallRisk
import org.suraksha.kavach.calls.CallShieldPlatform

data class SettingsState(
    val language: String = "en",
    val retentionDays: Int = 7,
    val protectionEnabled: Boolean = true,
    val enhanced: Boolean = false,
    val setupComplete: Boolean = false,
    val voiceEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val callShieldEnabled: Boolean = true,
    val reportedNumbers: Set<String> = emptySet(),
    val callActionEpoch: Long = 0L
)

class AppSettings(context: Context) {
    private val homeCountryCode = CallShieldPlatform.homeCountryCode(context)
    private val prefs = context.getSharedPreferences("kavach_preferences", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    private fun read() = SettingsState(
        language = language(prefs),
        retentionDays = prefs.getInt("retention_days", 7).takeIf { it in setOf(1, 7, 30) } ?: 7,
        protectionEnabled = prefs.getBoolean("protection", true),
        enhanced = prefs.getBoolean("enhanced", false),
        setupComplete = prefs.getBoolean("setup_complete", false),
        voiceEnabled = prefs.getBoolean("voice", true),
        overlayEnabled = prefs.getBoolean("overlay", false),
        callShieldEnabled = prefs.getBoolean("call_shield", true),
        reportedNumbers = prefs.getStringSet("reported_numbers", emptySet()).orEmpty()
            .mapNotNull { CallRisk.reportKey(it, homeCountryCode) }.take(MAX_REPORTED_NUMBERS).toSet(),
        callActionEpoch = prefs.getLong("call_action_epoch", 0L)
    )

    @Synchronized private fun change(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        mutable.value = read()
    }
    fun setLanguage(value: String) { require(value in setOf("en", "hi", "pa")); change { putString("language", value) } }
    fun setRetention(value: Int) { require(value in setOf(1, 7, 30)); change { putInt("retention_days", value) } }
    fun setProtection(value: Boolean) = change { putBoolean("protection", value) }
    fun setEnhanced(value: Boolean) = change { putBoolean("enhanced", value) }
    fun setVoice(value: Boolean) = change { putBoolean("voice", value) }
    fun setOverlay(value: Boolean) = change { putBoolean("overlay", value) }
    fun setCallShield(value: Boolean) = change {
        putBoolean("call_shield", value)
        if (!value) putLong("call_action_epoch", state.value.callActionEpoch + 1)
    }
    /** Stored on this phone only; used to flag a repeat scam caller before the phone rings. */
    @Synchronized fun reportNumber(number: String): Boolean {
        val normalized = CallRisk.reportKey(number, homeCountryCode) ?: return false
        if (normalized !in state.value.reportedNumbers && state.value.reportedNumbers.size >= MAX_REPORTED_NUMBERS) return false
        change { putStringSet("reported_numbers", state.value.reportedNumbers + normalized) }
        return true
    }
    fun removeReportedNumber(number: String) = change {
        val normalized = CallRisk.reportKey(number, homeCountryCode)
        putStringSet("reported_numbers", if (normalized == null) state.value.reportedNumbers else state.value.reportedNumbers - normalized)
        putLong("call_action_epoch", state.value.callActionEpoch + 1)
    }
    fun clearReportedNumbers() = change {
        remove("reported_numbers")
        putLong("call_action_epoch", state.value.callActionEpoch + 1)
    }
    fun wasAsked(permission: String): Boolean = prefs.getBoolean("asked_" + permission, false)
    fun markAsked(permission: String) = change { putBoolean("asked_" + permission, true) }
    fun finishSetup() = change { putBoolean("setup_complete", true) }
    fun reset() = change {
        val nextCallEpoch = state.value.callActionEpoch + 1
        clear()
        putBoolean("protection", false)
        putBoolean("call_shield", false)
        putLong("call_action_epoch", nextCallEpoch)
    }

    companion object {
        const val MAX_REPORTED_NUMBERS = 200
        private fun language(prefs: SharedPreferences) = prefs.getString("language", null)
            ?.takeIf { it in setOf("en", "hi", "pa") }
            ?: Locale.getDefault().language.takeIf { it in setOf("en", "hi", "pa") } ?: "en"

        fun localized(context: Context): Context {
            val prefs = context.getSharedPreferences("kavach_preferences", Context.MODE_PRIVATE)
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language(prefs)))
            return context.createConfigurationContext(config)
        }
    }
}
