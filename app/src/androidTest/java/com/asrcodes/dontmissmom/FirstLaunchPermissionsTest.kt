package com.asrcodes.dontmissmom

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Run on a fresh emulator installation (adb shell pm clear com.asrcodes.dontmissmom).
 * Exercises real Android permission dialogs; no account, recording, location lookup or SOS is used.
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchPermissionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val preferences get() = FirstLaunchPermissions.preferences(context)
    private var approximateChosen = false

    @Test fun firstLaunchSupportsSkipDenialGrantAndRotationWithoutRepeatingSetup() {
        AppDependencies.repositoryFactory = null
        FirebaseAuth.getInstance().signOut()
        assertFalse("Run this test on a fresh installation", FirstLaunchPermissions.granted(context, Manifest.permission.RECORD_AUDIO))
        preferences.edit().clear().commit()

        // The actual launcher offers setup before sign-in. Skipping shows one explanation.
        ActivityScenario.launch(LoginActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "runtimePermissionStep")), 10_000))
            onView(withId(R.id.btnDecline)).perform(scrollTo(), click())
            onView(withText(R.string.emergency_permissions_declined)).check(matches(isDisplayed()))
            onView(withText(R.string.got_it)).perform(click())
            onView(withId(R.id.cardGoogleSignIn)).check(matches(isDisplayed()))
            assertTrue(FirstLaunchPermissions.isComplete(context))
            assertFalse(preferences.getBoolean(FirstLaunchPermissions.NOTICE_PENDING, true))
        }
        ActivityScenario.launch(LoginActivity::class.java).use {
            onView(withId(R.id.cardGoogleSignIn)).check(matches(isDisplayed()))
            assertFalse(device.hasObject(By.res(context.packageName, "runtimePermissionStep")))
        }

        // A fresh setup requests every supported runtime permission. Deny all of them.
        preferences.edit().clear().commit()
        ActivityScenario.launch<LocationDisclosureActivity>(setupIntent()).use { scenario ->
            device.takeScreenshot(File(context.getExternalFilesDir(null), "permission-setup-${Build.VERSION.SDK_INT}.png"))
            onView(withId(R.id.btnContinue)).perform(scrollTo(), click())
            answerRuntimeDialogs(false)
            assertFalse(FirstLaunchPermissions.granted(context, Manifest.permission.RECORD_AUDIO))
            assertFalse(FirstLaunchPermissions.hasLocation(context))
            scenario.recreate()
            onView(withId(R.id.deliveryPermissionStep)).check(matches(isDisplayed()))
            onView(withId(R.id.btnFinishSetup)).perform(scrollTo(), click())
            onView(withText(R.string.emergency_permissions_declined)).check(matches(isDisplayed()))
            // Rotation preserves the single pending notice, then acknowledgment clears it.
            scenario.recreate()
            onView(withText(R.string.emergency_permissions_declined)).check(matches(isDisplayed()))
            onView(withText(R.string.got_it)).perform(click())
            onView(withId(R.id.cardGoogleSignIn)).check(matches(isDisplayed()))
            assertFalse(FirstLaunchPermissions.needsSetup(context))
        }
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.recreate()
            onView(withId(R.id.cardGoogleSignIn)).check(matches(isDisplayed()))
        }

        // Simulate a new setup and accept the real prompts, choosing approximate location.
        preferences.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 34) device.executeShellCommand("appops set ${context.packageName} USE_FULL_SCREEN_INTENT deny")
        setDndAccessForTest(false)
        ActivityScenario.launch<LocationDisclosureActivity>(setupIntent()).use { scenario ->
            onView(withId(R.id.btnContinue)).perform(scrollTo(), click())
            answerRuntimeDialogs(true)
            assertTrue(FirstLaunchPermissions.granted(context, Manifest.permission.RECORD_AUDIO))
            assertTrue(FirstLaunchPermissions.hasLocation(context))
            if (Build.VERSION.SDK_INT >= 31) {
                assertTrue("Approximate location option was exercised", approximateChosen)
                assertFalse(FirstLaunchPermissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            assertTrue(FirstLaunchPermissions.missingRuntimePermissions(context).isEmpty())
            assertTrue(preferences.getBoolean("location_disclosure_accepted", false))

            onView(withId(R.id.btnSetupDnd)).perform(scrollTo(), click())
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "deliveryPermissionStep")), 10_000))
            if (Build.VERSION.SDK_INT >= 34) {
                onView(withId(R.id.btnSetupFullScreen)).perform(scrollTo(), click())
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 10_000))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "deliveryPermissionStep")), 10_000))
                device.executeShellCommand("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
            }
            // Shell grants are confined to this emulator fixture, after verifying both links.
            setDndAccessForTest(true)
            assertTrue(context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
            scenario.moveToState(Lifecycle.State.CREATED).moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            onView(withId(R.id.deliveryPermissionStep)).check(matches(isDisplayed()))
            assertTrue(FirstLaunchPermissions.hasAllAccess(context))
            onView(withId(R.id.btnFinishSetup)).perform(scrollTo(), click())
            onView(withId(R.id.cardGoogleSignIn)).check(matches(isDisplayed()))
            assertFalse(preferences.getBoolean(FirstLaunchPermissions.NOTICE_PENDING, true))
        }
    }

    private fun setupIntent() = Intent(context, LocationDisclosureActivity::class.java)
        .putExtra(FirstLaunchPermissions.RETURN_TO_LOGIN, true)

    @Test fun incomingHistoryRouteSurvivesPermissionSetupForSignedInUser() {
        val repository = FakeRepository()
        AppDependencies.repositoryFactory = { repository }
        preferences.edit().clear().commit()
        val expectsNotice = !FirstLaunchPermissions.hasAllAccess(context)
        try {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra("toUid", repository.currentUid).putExtra("openHistory", true)
            ActivityScenario.launch<MainActivity>(intent).use {
                onView(withId(R.id.btnDecline)).perform(scrollTo(), click())
                if (expectsNotice) onView(withText(R.string.got_it)).perform(click())
                onView(withId(R.id.historyPage)).check(matches(isDisplayed()))
                assertTrue(FirstLaunchPermissions.isComplete(context))
            }
        } finally { AppDependencies.repositoryFactory = null }
    }

    private fun answerRuntimeDialogs(allow: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        val messages = mutableSetOf<String>()
        val buttonSelector = By.res(Pattern.compile(if (allow)
            ".*:id/permission_allow_(foreground_only_)?button" else ".*:id/permission_deny_button")).enabled(true)
        val messageSelector = By.res(Pattern.compile(".*:id/permission_message"))
        while (SystemClock.elapsedRealtime() < deadline) {
            if (device.hasObject(By.text(context.getString(R.string.setup_delivery_title)))) break
            val button = device.wait(Until.findObject(buttonSelector), 1_000)
            val message = device.wait(Until.findObject(messageSelector), 1_000)?.text
            if (button != null && message != null) {
                if (allow && Build.VERSION.SDK_INT >= 31) {
                    val coarse = By.res(Pattern.compile(".*:id/permission_location_accuracy_radio_coarse"))
                    if (device.hasObject(coarse) && !approximateChosen) selectApproximateLocation()
                }
                messages += message
                // Re-read the decision button after the precision-selection animation.
                tapSystemView(requireNotNull(device.wait(Until.findObject(buttonSelector), 1_000)))
                // Android reuses button IDs between groups. Wait for the actual message
                // to change rather than counting multiple clicks on the departing dialog.
                device.wait(Until.gone(By.text(message)), 5_000)
            }
        }
        assertEquals("Every runtime permission group must be offered: $messages", if (Build.VERSION.SDK_INT >= 33) 3 else 2, messages.size)
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.setup_delivery_title))), 5_000))
    }

    private fun selectApproximateLocation() {
        val resource = Pattern.compile(".*:id/permission_location_accuracy_radio_coarse")
        val selected = By.res(resource).checked(true)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline && !device.hasObject(selected)) {
            // The dialog can still be moving when its radio buttons become visible.
            // Reacquire the node and activate its accessibility click action so display
            // scaling or a stale center point cannot silently select the wrong control.
            val approximate = device.findObject(By.res(resource).enabled(true))
            val resourceId = approximate?.resourceName
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = resourceId?.let { id -> root?.findAccessibilityNodeInfosByViewId(id) }
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
            val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!clicked && approximate != null) tapSystemView(approximate)
            if (device.wait(Until.hasObject(selected), 500)) break
        }
        assertTrue("Android must select approximate before granting location", device.hasObject(selected))
        approximateChosen = true
    }

    private fun tapSystemView(view: UiObject2) {
        val center = view.visibleCenter
        // Use shell input for system permission controls and verify the resulting
        // state after each tap. Emulator display overrides and restored graphics
        // state can otherwise make injected touches miss these controls.
        device.executeShellCommand("input tap ${center.x} ${center.y}")
    }

    private fun setDndAccessForTest(allowed: Boolean) {
        if (Build.VERSION.SDK_INT >= 28) {
            val command = if (allowed) "allow_dnd" else "disallow_dnd"
            device.executeShellCommand("cmd notification $command ${context.packageName}")
            return
        }
        // Android 8 has no notification shell commands. Its PolicyAccess reads
        // this secure setting directly; preserve every other fixture package.
        val key = "enabled_notification_policy_access_packages"
        val packages = device.executeShellCommand("settings get secure $key").trim()
            .split(':').filter { it.isNotBlank() && it != "null" }.toMutableSet()
        check(packages.all { it.matches(Regex("[A-Za-z0-9_.]+")) })
        if (allowed) packages.add(context.packageName) else packages.remove(context.packageName)
        if (packages.isEmpty()) device.executeShellCommand("settings delete secure $key")
        else device.executeShellCommand("settings put secure $key ${packages.joinToString(":")}")
    }
}
