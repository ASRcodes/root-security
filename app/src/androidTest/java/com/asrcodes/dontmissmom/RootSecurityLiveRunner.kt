package com.asrcodes.dontmissmom

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Explicit live-provider runner. The production DmmApplication and saved configuration are used.
 * Caller filters/listeners are discarded: this entry point can run only the approved live check.
 * The default DmmTestRunner remains isolated and never contacts the reputation service.
 */
class RootSecurityLiveRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(Bundle().apply {
            putString("class", RootSecurityLiveUrlCheckTest::class.java.name)
        })
    }
}
