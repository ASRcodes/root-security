package com.asrcodes.dontmissmom

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Installation-level setup. Declining never prevents sign-in or sending a basic alert. */
object FirstLaunchPermissions {
    const val COMPLETE = "emergency_permission_setup_complete"
    const val RUNTIME_REQUESTED = "emergency_permissions_requested"
    const val NOTICE_PENDING = "emergency_permission_notice_pending"
    const val RETURN_TO_LOGIN = "permission_setup_return_to_login"

    fun preferences(context: Context) = context.getSharedPreferences(LocationDisclosureActivity.PREFS_NAME, Context.MODE_PRIVATE)
    fun isComplete(context: Context) = preferences(context).getBoolean(COMPLETE, false)
    fun needsSetup(context: Context) = !isComplete(context) || preferences(context).getBoolean(NOTICE_PENDING, false)
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context) = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun missingRuntimePermissions(context: Context): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS))
            add(Manifest.permission.POST_NOTIFICATIONS)
        // Approximate location is a valid choice; do not ask to upgrade it to precise location.
        if (!hasLocation(context)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
    }.toTypedArray()

    fun hasFullScreenAccess(context: Context) = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun hasAllAccess(context: Context): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return missingRuntimePermissions(context).isEmpty() && notifications.areNotificationsEnabled() &&
            notifications.isNotificationPolicyAccessGranted && hasFullScreenAccess(context)
    }
}
