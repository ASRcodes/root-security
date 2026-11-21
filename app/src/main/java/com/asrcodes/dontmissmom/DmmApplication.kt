package com.asrcodes.dontmissmom

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.suraksha.kavach.KavachApplication

/** The existing Firebase app and the local protection engine share one Android process. */
open class DmmApplication : KavachApplication() {
    @Volatile private var emergencyVisible = false

    override fun intrusiveWarningsAllowed(): Boolean = !emergencyVisible && !EmergencySoundService.isRunning

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                emergencyVisible = activity is AlertPopupActivity
                if (emergencyVisible) dismissWarnings()
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is AlertPopupActivity) emergencyVisible = false
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
