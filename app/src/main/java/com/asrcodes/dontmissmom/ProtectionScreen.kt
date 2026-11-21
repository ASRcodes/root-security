package com.asrcodes.dontmissmom

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.suraksha.kavach.KavachApplication
import org.suraksha.kavach.LoadState
import org.suraksha.kavach.MainActivity as ScamProtectionActivity

/** Host navigation into local protection; opening a screen never sends an SOS or opts into SMS. */
class ProtectionScreen(private val activity: MainActivity) {
    private val graph = activity.application as KavachApplication

    init {
        listOf(
            R.id.btnProtectionCheck to "CHECK",
            R.id.btnProtectionFiles to "FILES",
            R.id.btnProtectionSetup to "SETUP",
            R.id.btnProtectionGuardian to "GUARDIAN",
            R.id.btnProtectionCode to "CODE",
            R.id.btnProtectionCall to "CALL",
            R.id.btnProtectionHistory to "ACTIVITY",
            R.id.btnProtectionSettings to "SETTINGS"
        ).forEach { (id, page) ->
            activity.findViewById<View>(id).setOnClickListener {
                activity.startActivity(Intent(activity, ScamProtectionActivity::class.java).putExtra("page", page))
            }
        }
        activity.findViewById<View>(R.id.btnProtectionRetry).setOnClickListener { graph.retryModels() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(graph.modelState, graph.listenerConnected, graph.settings.state, graph.storageState, graph.online.state) { _, _, _, _, _ -> Unit }
                    .collect { refresh() }
            }
        }
        refresh()
    }

    /** Re-read Android access on resume as settings can change outside our process. */
    fun refresh() {
        val settings = graph.settings.state.value
        val model = graph.modelState.value
        val listenerAllowed = activity.packageName in NotificationManagerCompat.getEnabledListenerPackages(activity)
        val listenerConnected = listenerAllowed && graph.listenerConnected.value
        val warningsReady = NotificationManagerCompat.from(activity).areNotificationsEnabled() &&
            graph.alerts.channelsReady(settings.language)
        val status = when {
            model == LoadState.FAILED -> R.string.protection_failed to R.string.protection_failed_detail
            model == LoadState.LOADING -> R.string.protection_loading to R.string.protection_loading_detail
            !settings.protectionEnabled -> R.string.protection_paused to R.string.protection_paused_detail
            !settings.setupComplete -> R.string.protection_setup to R.string.protection_setup_detail
            !listenerAllowed -> R.string.protection_access to R.string.protection_access_detail
            !listenerConnected -> R.string.protection_waiting to R.string.protection_waiting_detail
            !warningsReady -> R.string.protection_limited to R.string.protection_limited_detail
            else -> R.string.protection_active to R.string.protection_active_detail
        }
        activity.findViewById<TextView>(R.id.tvProtectionStatus).apply {
            setText(status.first)
            statusTone(status.first == R.string.protection_active)
        }
        activity.findViewById<TextView>(R.id.tvProtectionDetail).setText(status.second)
        activity.findViewById<TextView>(R.id.tvProtectionModelStatus).apply {
            setText(when (model) {
                LoadState.READY -> R.string.protection_models_ready
                LoadState.LOADING -> R.string.protection_models_loading
                LoadState.FAILED -> R.string.protection_models_failed
            })
            statusTone(model == LoadState.READY)
        }
        activity.findViewById<TextView>(R.id.tvProtectionListenerStatus).apply {
            setText(if (listenerConnected) R.string.protection_listener_connected else R.string.protection_listener_off)
            statusTone(listenerConnected)
        }
        activity.findViewById<TextView>(R.id.tvProtectionStorageStatus).apply {
            setText(when (graph.storageState.value) {
                LoadState.READY -> R.string.protection_storage_ready
                LoadState.LOADING -> R.string.protection_storage_loading
                LoadState.FAILED -> R.string.protection_storage_failed
            })
            statusTone(graph.storageState.value == LoadState.READY)
        }
        activity.findViewById<TextView>(R.id.tvProtectionOnlineStatus).apply {
            val online = graph.online.state.value
            setText(if (online.enabled && online.keyConfigured) R.string.protection_online_enabled else R.string.protection_online_missing)
            statusTone(false) // Enabled is configuration, never proof of a successful URL check.
        }
        activity.findViewById<View>(R.id.btnProtectionRetry).show(model == LoadState.FAILED)
    }
}
