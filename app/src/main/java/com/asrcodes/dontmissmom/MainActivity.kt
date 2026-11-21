package com.asrcodes.dontmissmom

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : BaseActivity() {
    private lateinit var model: MainViewModel
    private lateinit var navigation: BottomNavigationView
    private lateinit var adapter: TrustedContactAdapter
    private lateinit var homeAdapter: TrustedContactAdapter
    private var historyScreen: HistoryScreen? = null
    private var protectionScreen: ProtectionScreen? = null
    private var updatingSwitch = false
    private var networkRegistered = false
    private var alertObserver: ForegroundAlertObserver? = null
    private var displayedOutcome: SendOutcome? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { updateConnection(); if (::model.isInitialized) { model.repository.syncToken(); VoiceRegistrationQueue.sync(this@MainActivity) } } }
        override fun onLost(network: Network) { runOnUiThread { updateConnection() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { runOnUiThread { updateConnection() } }
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.record() else permissionHelp(R.string.voice_permission)
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        updateSetup(); if (!granted) permissionHelp(R.string.permission_denied)
    }
    private val location = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        updateSetup(); if (grants.values.none { it }) permissionHelp(R.string.permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RequestNotifications.createChannel(this)
        model = ViewModelProvider(this)[MainViewModel::class.java]
        if (model.repository.currentUid == null) {
            startActivity(Intent(this, LoginActivity::class.java).putExtras(intent))
            finish()
            return
        }
        if (FirstLaunchPermissions.needsSetup(this)) {
            startActivity(Intent(this, LocationDisclosureActivity::class.java).putExtras(intent))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        navigation = findViewById(R.id.bottomNavigation)
        navigation.setOnItemSelectedListener { item -> showPage(item.itemId); true }
        navigation.selectedItemId = savedInstanceState?.getInt("selected_tab") ?: R.id.nav_home
        showPage(navigation.selectedItemId)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.selectedItemId != R.id.nav_home) navigation.selectedItemId = R.id.nav_home
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        adapter = TrustedContactAdapter(mutableListOf(), { confirmSend(listOf(it)) }, { showContactOptions(it) })
        findViewById<RecyclerView>(R.id.recyclerTrustedContacts).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        homeAdapter = TrustedContactAdapter(mutableListOf(), { confirmSend(listOf(it)) }, { showContactOptions(it) })
        findViewById<RecyclerView>(R.id.recyclerHomeContacts).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = homeAdapter
        }
        historyScreen = HistoryScreen(this, model)
        protectionScreen = ProtectionScreen(this)
        click(R.id.btnOpenProtection) { navigation.selectedItemId = R.id.nav_protection }
        click(R.id.btnHomeRetry) { model.reload() }
        click(R.id.btnViewCircle) { navigation.selectedItemId = R.id.nav_circle }
        click(R.id.btnReviewSetup) { navigation.selectedItemId = R.id.nav_settings }
        click(R.id.btnAddContact) { phoneDialog(false) }
        click(R.id.btnEditPhone) { phoneDialog(true) }
        click(R.id.btnRetryContacts) { model.reload() }
        click(R.id.btnNotifications) { startActivity(Intent(this, RequestsActivity::class.java)) }
        click(R.id.btnRequests) { startActivity(Intent(this, RequestsActivity::class.java)) }
        click(R.id.btnEmergency) { confirmSend(model.contacts.value.orEmpty()) }
        click(R.id.btnRecordVoice) {
            if (model.voice.value?.mode == "ready") {
                MaterialAlertDialogBuilder(this).setTitle(R.string.remove_voice)
                    .setPositiveButton(R.string.add_voice) { _, _ -> requestRecording() }
                    .setNegativeButton(R.string.cancel, null).show()
            } else requestRecording()
        }
        click(R.id.btnClearVoice) { model.clearVoice() }
        click(R.id.btnNotificationSettings) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openExternal(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        click(R.id.btnDndSettings) { openExternal(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        click(R.id.btnAppPermissions) { openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        click(R.id.btnFullScreenSettings) {
            if (Build.VERSION.SDK_INT >= 34) openExternal(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + packageName)))
        }
        click(R.id.btnLocationSettings) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.location_title).setMessage(R.string.location_disclosure)
                .setPositiveButton(R.string.allow_location) { _, _ ->
                    getSharedPreferences(LocationDisclosureActivity.PREFS_NAME, MODE_PRIVATE).edit().putBoolean("location_disclosure_accepted", true).apply()
                    location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }.setNegativeButton(R.string.cancel, null).show()
        }
        click(R.id.btnLogout) { signOut() }
        findViewById<MaterialSwitch>(R.id.switchSiren).setOnCheckedChangeListener { _, checked ->
            if (!updatingSwitch) {
                if (checked) { SirenSettings(this).enable(); updateSetup() }
                else {
                    updateSetup()
                    MaterialAlertDialogBuilder(this).setTitle(R.string.mute_title)
                        .setItems(arrayOf(getString(R.string.mute_six_hours), getString(R.string.mute_indefinitely))) { _, which ->
                            SirenSettings(this).mute(which == 1)
                            stopService(Intent(this, EmergencySoundService::class.java))
                            updateSetup()
                        }.setNegativeButton(R.string.cancel, null).show()
                }
            }
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        observeState()
        if (savedInstanceState == null) routeNotification(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); routeNotification(intent) }

    private fun routeNotification(intent: Intent) {
        if (intent.getStringExtra("toUid") != model.repository.currentUid) return
        if (intent.getBooleanExtra("openHistory", false)) navigation.selectedItemId = R.id.nav_history
        else if (intent.action == RequestNotifications.ACTION || intent.getStringExtra("type") == RequestNotifications.TYPE) {
            navigation.selectedItemId = R.id.nav_circle
            startActivity(Intent(this, RequestsActivity::class.java).putExtra("requestId", intent.getStringExtra("requestId")))
        }
    }

    private fun showPage(id: Int) {
        if (id != R.id.nav_history) historyScreen?.stop()
        if (id == R.id.nav_protection) protectionScreen?.refresh()
        val pages = listOf(R.id.homePage to R.id.nav_home, R.id.circlePage to R.id.nav_circle, R.id.settingsPage to R.id.nav_settings, R.id.historyPage to R.id.nav_history, R.id.protectionPage to R.id.nav_protection)
        pages.forEach { (pageId, tabId) ->
            val page = findViewById<View>(pageId)
            val entering = id == tabId && page.visibility != View.VISIBLE
            page.animate().cancel()
            page.alpha = 1f
            page.translationY = 0f
            page.show(id == tabId)
            if (entering && ValueAnimator.areAnimatorsEnabled()) {
                page.alpha = 0f
                page.translationY = 6 * resources.displayMetrics.density
                page.animate().alpha(1f).translationY(0f).setDuration(160)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun observeState() {
        model.profile.observe(this) { profile ->
            if (profile != null) {
                findViewById<TextView>(R.id.tvWelcome).text = getString(R.string.greeting, profile.name.substringBefore(" ").ifBlank { getString(R.string.unknown_sender) })
                findViewById<TextView>(R.id.tvProfileName).text = profile.name
                findViewById<TextView>(R.id.tvProfileEmail).text = profile.email
                findViewById<TextView>(R.id.tvPhone).text = profile.phone
            }
        }
        model.profileError.observe(this) { error ->
            if (error) Snackbar.make(findViewById(R.id.mainRoot), R.string.profile_error, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.retry) { model.reload() }.show()
        }
        model.contacts.observe(this) { contacts ->
            adapter.setData(contacts)
            homeAdapter.setData(contacts)
            findViewById<TextView>(R.id.tvCircleSummary).text =
                if (contacts.isEmpty()) getString(R.string.empty_circle_body) else resources.getQuantityString(R.plurals.circle_count, contacts.size, contacts.size)
            updateContactsState()
        }
        model.contactsLoading.observe(this) { updateContactsState() }
        model.contactsError.observe(this) { updateContactsState() }
        model.requests.observe(this) { requests ->
            if (requests.isEmpty()) navigation.removeBadge(R.id.nav_circle)
            else navigation.getOrCreateBadge(R.id.nav_circle).apply {
                number = requests.size
                backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.info)
                badgeTextColor = ContextCompat.getColor(this@MainActivity, R.color.on_info)
            }
            findViewById<MaterialButton>(R.id.btnRequests).text = if (requests.isEmpty()) getString(R.string.requests) else getString(R.string.requests_count, requests.size)
            findViewById<View>(R.id.btnNotifications).contentDescription = resources.getQuantityString(R.plurals.requests_description, requests.size, requests.size)
        }
        model.sending.observe(this) { renderActions() }
        model.sendingContacts.observe(this) { renderActions() }
        model.voice.observe(this) { renderActions() }
        model.voiceError.observe(this) { if (it) { message(R.string.voice_error); model.voiceError.value = false } }
        model.outcome.observe(this) { result ->
            if (result != null && displayedOutcome !== result) {
                displayedOutcome = result
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle(if (result.accepted > 0) getString(R.string.send_result, result.accepted, result.total)
                        else getString(if ((result.error as? AlertProblem)?.unconfirmed != false) R.string.alert_unconfirmed_title else R.string.send_failed))
                val confirmed = result.accepted == result.total
                val rejected = result.accepted == 0 && (result.error as? AlertProblem)?.unconfirmed == false
                val statusIcon = if (confirmed) R.drawable.ic_check_circle else if (rejected) R.drawable.ic_alert_signal else R.drawable.ic_warning
                val statusColor = if (confirmed) R.color.success_ink else if (rejected) R.color.danger else R.color.warning_ink
                dialog.setIcon(ContextCompat.getDrawable(this, statusIcon)?.mutate()?.apply {
                    setTint(ContextCompat.getColor(this@MainActivity, statusColor))
                })
                if (result.accepted < result.total) {
                    dialog.setMessage((result.error as? AlertProblem)?.resource ?: (result.error as? AppProblem)?.resource ?: R.string.send_partial)
                    if (result.retryIds.isNotEmpty()) dialog.setPositiveButton(R.string.retry) { _, _ ->
                        if (isOnline()) model.retryFailed(result.retryIds) else message(R.string.connection_error)
                    }
                    dialog.setNegativeButton(android.R.string.ok, null)
                } else dialog.setMessage(R.string.send_accepted).setPositiveButton(android.R.string.ok, null)
                dialog.setOnDismissListener {
                    displayedOutcome = null
                    if (!isChangingConfigurations) model.consumeOutcome(result)
                }
                dialog.show()
            }
        }
    }

    private fun updateContactsState() {
        val loading = model.contactsLoading.value == true
        val error = model.contactsError.value == true
        val empty = model.contacts.value.isNullOrEmpty()
        findViewById<View>(R.id.homeContactsProgress).show(loading)
        findViewById<View>(R.id.homeContactsState).show(!loading && (empty || error))
        findViewById<TextView>(R.id.homeContactsState).setText(if (error) R.string.contacts_error else R.string.empty_circle_body)
        findViewById<View>(R.id.btnHomeRetry).show(error)
        findViewById<View>(R.id.contactsProgress).show(loading)
        findViewById<View>(R.id.contactsState).show(!loading && (empty || error))
        findViewById<View>(R.id.btnRetryContacts).show(error)
        findViewById<TextView>(R.id.tvContactsStateTitle).setText(if (error) R.string.contacts_error else R.string.empty_circle_title)
        findViewById<TextView>(R.id.tvContactsStateBody).setText(if (error) R.string.connection_error else R.string.empty_circle_body)
    }

    private fun renderActions() {
        val sending = model.sending.value == true
        val state = model.voice.value ?: VoiceState()
        val recording = state.mode == "recording" || state.mode == "uploading"
        findViewById<MaterialButton>(R.id.btnEmergency).apply {
            isEnabled = !sending && !recording
            setText(if (sending) R.string.sending_sos else R.string.send_sos)
        }
        adapter.sendingContacts = model.sendingContacts.value.orEmpty()
        adapter.preparingVoice = recording
        homeAdapter.sendingContacts = model.sendingContacts.value.orEmpty()
        homeAdapter.preparingVoice = recording
        findViewById<MaterialButton>(R.id.btnRecordVoice).apply {
            isEnabled = !sending && !recording
            text = when (state.mode) {
                "recording" -> resources.getQuantityString(R.plurals.voice_recording, state.seconds, state.seconds)
                "uploading" -> getString(R.string.voice_uploading)
                "ready" -> getString(R.string.voice_ready)
                else -> getString(R.string.add_voice)
            }
        }
        findViewById<View>(R.id.btnClearVoice).apply { show(state.mode == "ready"); isEnabled = !sending }
        findViewById<View>(R.id.tvVoiceBadge).show(state.mode == "ready")
    }

    private fun requestRecording() {
        if (!isOnline()) return message(R.string.offline)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.record()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun permissionHelp(message: Int) {
        Snackbar.make(findViewById(R.id.mainRoot), message, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_settings) {
                openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }

    private fun confirmSend(contacts: List<TrustedContact>) {
        if (model.voice.value?.mode in listOf("recording", "uploading")) return
        val eligible = contacts.filterNot { it.blocked || it.uid in model.sendingContacts.value.orEmpty() }
        if (eligible.isEmpty()) { message(R.string.no_recipients); navigation.selectedItemId = R.id.nav_circle; return }
        if (!isOnline()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.offline).setPositiveButton(R.string.offline_sms) { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle(R.string.offline_sms)
                    .setItems(eligible.map { it.username }.toTypedArray()) { _, index ->
                        openExternal(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + eligible[index].phone))
                            .putExtra("sms_body", getString(R.string.sms_body, "")))
                    }.setNegativeButton(R.string.cancel, null).show()
            }.setNegativeButton(R.string.cancel, null).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm_sos)
            .setIcon(R.drawable.ic_alert_signal)
            .setMessage(if (eligible.size == 1) getString(R.string.confirm_contact, eligible[0].username) else resources.getQuantityString(R.plurals.confirm_sos_body, eligible.size, eligible.size))
            .setPositiveButton(R.string.send_sos) { _, _ -> model.sendTo(eligible) }
            .setNegativeButton(R.string.cancel, null).show()
            .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.primary))
    }

    private fun phoneDialog(edit: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<TextInputEditText>(R.id.dialogEditText)
        val wrapper = view.findViewById<TextInputLayout>(R.id.inputLayout)
        view.findViewById<TextView>(R.id.dialogSubtitle).setText(if (edit) R.string.phone_help else R.string.request_contact_body)
        if (edit) input.setText(model.profile.value?.phone.orEmpty())
        input.doAfterTextChanged { wrapper.error = null }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (edit) R.string.edit_phone else R.string.add_contact_short)
            .setView(view).setPositiveButton(if (edit) R.string.save else R.string.send_request, null)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val phone = PhoneNumbers.normalize(input.text.toString())
                if (phone == null) { wrapper.error = getString(R.string.phone_invalid); return@setOnClickListener }
                if (!isOnline()) { wrapper.error = getString(R.string.connection_error); return@setOnClickListener }
                positive.isEnabled = false
                val complete: (Result<Unit>) -> Unit = { result ->
                    if (!isDestroyed) {
                        positive.isEnabled = true
                        if (result.isSuccess) { dialog.dismiss(); message(if (edit) R.string.contact_updated else R.string.request_sent) }
                        else { failure(result.exceptionOrNull()) }
                    }
                }
                if (edit) model.repository.updatePhone(phone, complete) else model.repository.addContact(phone, complete)
            }
        }
        dialog.show()
    }

    private fun showContactOptions(contact: TrustedContact) {
        MaterialAlertDialogBuilder(this).setTitle(contact.username)
            .setItems(arrayOf(getString(if (contact.blocked) R.string.unblock else R.string.block), getString(R.string.delete_contact))) { _, which ->
                if (!isOnline()) message(R.string.connection_error)
                else if (which == 0) model.repository.setBlocked(contact, !contact.blocked) { result ->
                    if (!isDestroyed) { if (result.isSuccess) message(R.string.contact_updated) else failure(result.exceptionOrNull()) }
                } else MaterialAlertDialogBuilder(this).setTitle(R.string.delete_contact)
                    .setMessage(getString(R.string.delete_contact_body, contact.username))
                    .setPositiveButton(R.string.delete_contact) { _, _ ->
                        model.repository.removeContact(contact) { result ->
                            if (!isDestroyed) { if (result.isSuccess) message(R.string.contact_removed) else failure(result.exceptionOrNull()) }
                        }
                    }.setNegativeButton(R.string.cancel, null).show()
            }.show()
    }

    private fun updateSetup() {
        if (!::navigation.isInitialized) return
        val settings = SirenSettings(this)
        val enabled = settings.enabled()
        updatingSwitch = true
        findViewById<MaterialSwitch>(R.id.switchSiren).isChecked = enabled
        updatingSwitch = false
        findViewById<TextView>(R.id.tvSirenStatus).setText(if (enabled) R.string.siren_on else if (settings.permanent()) R.string.siren_off else R.string.siren_muted)
        findViewById<TextView>(R.id.tvSirenStatus).statusTone(enabled)
        val manager = getSystemService(NotificationManager::class.java)
        findViewById<TextView>(R.id.tvNotificationStatus).setText(if (manager.areNotificationsEnabled()) R.string.enabled else R.string.needs_setup)
        findViewById<TextView>(R.id.tvNotificationStatus).statusTone(manager.areNotificationsEnabled())
        findViewById<TextView>(R.id.tvDndStatus).setText(if (manager.isNotificationPolicyAccessGranted) R.string.enabled else R.string.needs_setup)
        findViewById<TextView>(R.id.tvDndStatus).statusTone(manager.isNotificationPolicyAccessGranted)
        findViewById<TextView>(R.id.tvFullScreenStatus).setText(if (Build.VERSION.SDK_INT < 34) R.string.managed_by_android else if (manager.canUseFullScreenIntent()) R.string.enabled else R.string.needs_setup)
        findViewById<TextView>(R.id.tvFullScreenStatus).statusTone(Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())
        findViewById<View>(R.id.btnFullScreenSettings).isEnabled = Build.VERSION.SDK_INT >= 34
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.tvLocationStatus).setText(if (hasLocation) R.string.location_included else R.string.location_optional)
        updateConnection()
    }

    private fun updateConnection() { if (::navigation.isInitialized) findViewById<View>(R.id.offlineBanner).show(!isOnline()) }

    private fun signOut() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.sign_out).setMessage(R.string.sign_out_body)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                stopService(Intent(this, EmergencySoundService::class.java))
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {
                    // A different account may have signed in while token deletion was pending.
                    if (FirebaseAuth.getInstance().currentUser != null) FirebaseAppRepository().syncToken()
                }
                FirebaseAuth.getInstance().signOut()
                GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun click(id: Int, action: () -> Unit) { findViewById<View>(id).setOnClickListener { action() } }

    override fun onStart() {
        super.onStart()
        if (::navigation.isInitialized) {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
            if (model.repository is FirebaseAppRepository) {
                model.repository.currentUid?.let { alertObserver = ForegroundAlertObserver(this, it) }
            }
        }
    }
    override fun onResume() { super.onResume(); updateSetup(); protectionScreen?.refresh(); if (::model.isInitialized) { model.repository.syncToken(); VoiceRegistrationQueue.sync(this@MainActivity) } }
    override fun onStop() {
        historyScreen?.stop()
        alertObserver?.remove(); alertObserver = null
        if (networkRegistered) { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback); networkRegistered = false }
        if (::model.isInitialized && !isChangingConfigurations) model.cancelRecording()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::navigation.isInitialized) outState.putInt("selected_tab", navigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }
}
