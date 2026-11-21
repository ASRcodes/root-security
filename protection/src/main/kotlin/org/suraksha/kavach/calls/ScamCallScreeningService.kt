package org.suraksha.kavach.calls

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import org.suraksha.core.CallRisk
import org.suraksha.core.CallRiskLevel
import org.suraksha.kavach.KavachApplication

/**
 * Warns about risky callers before the user answers. It never blocks, rejects or records a call,
 * and it only sees the caller ID: Android does not let ordinary apps hear call audio.
 * Android normally excludes saved contacts and does not deliver restricted/hidden caller IDs.
 * See https://developer.android.com/reference/android/telecom/CallScreeningService#onScreenCall(android.telecom.Call.Details)
 */
class ScamCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        if (Build.VERSION.SDK_INT >= 29 && details.callDirection != Call.Details.DIRECTION_INCOMING) return
        // Always allow the call to ring normally; the warning is advisory.
        respondToCall(details, CallResponse.Builder().build())
        val app = application as? KavachApplication ?: return
        val settings = app.settings.state.value
        if (!settings.callShieldEnabled || app.resetInProgress.value || !CallShieldPlatform.roleHeld(app)) return
        if (details.handle != null && details.handle?.scheme != "tel") return
        val number = details.handle?.schemeSpecificPart
        val hidden = details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val verdict = CallRisk.evaluate(number, hidden, knownContact = false,
            homeCountryCode = CallShieldPlatform.homeCountryCode(app), reported = settings.reportedNumbers)
        if (verdict.level != CallRiskLevel.NONE) CallAlerts.show(app, number, verdict)
    }

}
