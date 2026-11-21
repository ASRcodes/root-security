package org.suraksha.kavach.calls

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

object CallShieldPlatform {
    fun roleHeld(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true

    fun homeCountryCode(context: Context): String {
        val iso = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.let {
                it.simCountryIso.ifBlank { it.networkCountryIso }
            }
        }.getOrNull().orEmpty().lowercase()
        return COUNTRY_CODES[iso] ?: "91"
    }

    private val COUNTRY_CODES = mapOf("in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sa" to "966",
        "sg" to "65", "au" to "61", "de" to "49", "fr" to "33", "np" to "977", "bd" to "880", "lk" to "94")
}
