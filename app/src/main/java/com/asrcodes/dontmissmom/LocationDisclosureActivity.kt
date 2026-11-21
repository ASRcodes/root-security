package com.asrcodes.dontmissmom

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LocationDisclosureActivity : BaseActivity() {
    companion object {
        const val PREFS_NAME = "app_prefs"
        const val KEY_DISCLOSURE_SHOWN = "location_disclosure_shown"
    }
    private var deliveryStep = false
    private var requesting = false
    private var notice: AlertDialog? = null
    private val preferences by lazy { FirstLaunchPermissions.preferences(this) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        showDeliveryStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_disclosure)
        deliveryStep = savedInstanceState?.getBoolean("delivery_step")
            ?: preferences.getBoolean(FirstLaunchPermissions.RUNTIME_REQUESTED, false)
        requesting = savedInstanceState?.getBoolean("requesting") ?: false
        findViewById<View>(R.id.btnContinue).setOnClickListener {
            if (requesting) return@setOnClickListener
            preferences.edit().putBoolean(KEY_DISCLOSURE_SHOWN, true)
                .putBoolean("location_disclosure_accepted", true)
                .putBoolean(FirstLaunchPermissions.RUNTIME_REQUESTED, true).apply()
            val missing = FirstLaunchPermissions.missingRuntimePermissions(this)
            if (missing.isEmpty()) showDeliveryStep()
            else { requesting = true; render(); permission.launch(missing) }
        }
        findViewById<View>(R.id.btnDecline).setOnClickListener { finishSetup() }
        findViewById<View>(R.id.btnFinishSetup).setOnClickListener { finishSetup() }
        findViewById<View>(R.id.btnSetupNotifications).setOnClickListener {
            openExternal(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<View>(R.id.btnSetupDnd).setOnClickListener {
            openExternal(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.btnSetupFullScreen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34)
                openExternal(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!requesting) finishSetup() }
        })
        render()
        if (FirstLaunchPermissions.isComplete(this)) {
            if (preferences.getBoolean(FirstLaunchPermissions.NOTICE_PENDING, false)) showNotice() else openNext()
        }
    }

    override fun onResume() { super.onResume(); render() }

    private fun showDeliveryStep() {
        requesting = false
        deliveryStep = true
        render()
        findViewById<NestedScrollView>(R.id.disclosureRoot).post {
            if (!isDestroyed) findViewById<NestedScrollView>(R.id.disclosureRoot).scrollTo(0, 0)
        }
    }

    private fun render() {
        findViewById<View>(R.id.runtimePermissionStep).show(!deliveryStep)
        findViewById<View>(R.id.deliveryPermissionStep).show(deliveryStep)
        findViewById<View>(R.id.btnContinue).isEnabled = !requesting
        findViewById<View>(R.id.btnDecline).isEnabled = !requesting
        val manager = getSystemService(NotificationManager::class.java)
        findViewById<View>(R.id.setupNotifications).show(!manager.areNotificationsEnabled())
        findViewById<TextView>(R.id.setupDndStatus).setText(if (manager.isNotificationPolicyAccessGranted) R.string.enabled else R.string.needs_setup)
        findViewById<View>(R.id.btnSetupDnd).isEnabled = !manager.isNotificationPolicyAccessGranted
        findViewById<View>(R.id.setupFullScreen).show(Build.VERSION.SDK_INT >= 34)
        findViewById<TextView>(R.id.setupFullScreenStatus).setText(if (FirstLaunchPermissions.hasFullScreenAccess(this)) R.string.enabled else R.string.needs_setup)
        findViewById<View>(R.id.btnSetupFullScreen).isEnabled = !FirstLaunchPermissions.hasFullScreenAccess(this)
    }

    private fun finishSetup() {
        if (requesting || notice?.isShowing == true) return
        val declined = !FirstLaunchPermissions.hasAllAccess(this)
        preferences.edit().putBoolean(KEY_DISCLOSURE_SHOWN, true)
            .putBoolean(FirstLaunchPermissions.COMPLETE, true)
            .putBoolean(FirstLaunchPermissions.NOTICE_PENDING, declined).apply()
        if (declined) showNotice() else openNext()
    }

    private fun showNotice() {
        if (notice?.isShowing == true) return
        notice = MaterialAlertDialogBuilder(this).setTitle(R.string.emergency_permissions_title)
            .setMessage(R.string.emergency_permissions_declined)
            .setPositiveButton(R.string.got_it) { _, _ -> acknowledgeNotice() }
            .setOnCancelListener { acknowledgeNotice() }.show()
    }

    private fun acknowledgeNotice() {
        preferences.edit().putBoolean(FirstLaunchPermissions.NOTICE_PENDING, false).apply()
        openNext()
    }

    private fun openNext() {
        val target = if (intent.getBooleanExtra(FirstLaunchPermissions.RETURN_TO_LOGIN, false) ||
            AppDependencies.repository().currentUid == null) LoginActivity::class.java else MainActivity::class.java
        val next = Intent(this, target).putExtras(intent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        next.removeExtra(FirstLaunchPermissions.RETURN_TO_LOGIN)
        startActivity(next)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("delivery_step", deliveryStep)
        outState.putBoolean("requesting", requesting)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { notice?.dismiss(); notice = null; super.onDestroy() }
}
