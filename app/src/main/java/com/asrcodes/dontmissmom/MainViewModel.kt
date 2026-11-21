package com.asrcodes.dontmissmom

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class SendOutcome(val accepted: Int, val total: Int, val error: Throwable? = null, val retryIds: Set<String> = emptySet())
data class VoiceState(val mode: String = "empty", val seconds: Int = 0, val url: String? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AppDependencies.repository()
    val profile = MutableLiveData<UserProfile?>()
    val contacts = MutableLiveData<List<TrustedContact>>(emptyList())
    val requests = MutableLiveData<List<RequestItem>>(emptyList())
    val history = MutableLiveData<List<EmergencyHistory>>(emptyList())
    val historyLoading = MutableLiveData(true)
    val historyError = MutableLiveData(false)
    val historyHasMore = MutableLiveData(false)
    private var historyLimit = 50
    private var historySubscription: Subscription? = null
    private var historyGeneration = 0
    val contactsLoading = MutableLiveData(true)
    val contactsError = MutableLiveData(false)
    val profileError = MutableLiveData(false)
    val sending = MutableLiveData(false)
    val sendingContacts = MutableLiveData<Set<String>>(emptySet())
    val outcome = MutableLiveData<SendOutcome?>()
    val voice = MutableLiveData(VoiceState())
    val voiceError = MutableLiveData(false)
    private val subscriptions = mutableListOf<Subscription>()
    private var recorder: VoiceRecorderHelper? = null
    private var voiceGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private val locationCancellations = mutableSetOf<CancellationTokenSource>()
    private data class PendingAlert(val contact: TrustedContact, val id: String = UUID.randomUUID().toString(),
        var attachment: AlertAttachment? = null, var unconfirmed: Boolean = false)
    private val pendingAlerts = mutableMapOf<String, PendingAlert>()
    private val alertRequests = mutableMapOf<String, Subscription>()
    private val outcomes = ArrayDeque<SendOutcome>()
    private var lastRetryIds = emptySet<String>()
    private var cleared = false

    init { reload(); repository.syncToken() }

    fun reload() {
        subscriptions.forEach { it.remove() }; subscriptions.clear()
        if (repository.currentUid == null) {
            historySubscription?.remove(); historyGeneration++
            history.value = emptyList(); historyHasMore.value = false; historyLoading.value = false
            return
        }
        contactsLoading.value = true
        contactsError.value = false
        historyLoading.value = true
        historyError.value = false
        subscriptions += repository.observeProfile { result ->
            profileError.value = result.isFailure
            result.getOrNull()?.let { profile.value = it }
        }
        subscriptions += repository.observeContacts { result ->
            contactsLoading.value = false
            contactsError.value = result.isFailure
            result.getOrNull()?.let { contacts.value = it }
        }
        subscriptions += repository.observeRequests { result -> result.getOrNull()?.let { requests.value = it } }
        reloadHistory()
    }

    fun reloadHistory() {
        val generation = ++historyGeneration
        historySubscription?.remove()
        historyLoading.value = true
        historyError.value = false
        historySubscription = repository.observeHistory(historyLimit) { result ->
            if (cleared || generation != historyGeneration) return@observeHistory
            historyLoading.value = false
            historyError.value = result.isFailure
            result.getOrNull()?.let { history.value = it.items; historyHasMore.value = it.hasMore }
        }
    }

    fun loadMoreHistory() {
        if (historyLoading.value == true || historyHasMore.value != true) return
        historyLimit += 50
        reloadHistory()
    }

    fun record() {
        if (voice.value?.mode in listOf("recording", "uploading") || sending.value == true) return
        recorder?.release()
        val version = ++voiceGeneration
        voiceError.value = false
        voice.value = VoiceState("recording", 5)
        recorder = VoiceRecorderHelper(getApplication(),
            onCountdownTick = { seconds -> handler.post { if (version == voiceGeneration) voice.value = VoiceState("recording", seconds) } },
            onRecordingComplete = { handler.post { if (version == voiceGeneration) voice.value = VoiceState("uploading") } },
            onUploadSuccess = { url -> handler.post { if (version == voiceGeneration) voice.value = VoiceState("ready", url = url) } },
            onError = { handler.post { if (version == voiceGeneration) { voice.value = VoiceState(); voiceError.value = true } } })
        recorder?.startRecording()
    }

    fun clearVoice() { voiceGeneration++; recorder?.release(); recorder = null; voice.value = VoiceState() }
    fun cancelRecording() { if (voice.value?.mode == "recording") clearVoice() }

    fun sendTo(selected: List<TrustedContact>) {
        if (cleared || voice.value?.mode in listOf("recording", "uploading")) return
        val recipients = selected.filterNot { it.blocked || it.uid in sendingContacts.value.orEmpty() }.distinctBy { it.uid }
        if (recipients.isEmpty()) return
        // A tap after an unconfirmed result resumes that alert, including its ID and attachment.
        val attempt = recipients.map { contact -> pendingAlerts.getOrPut(contact.uid) { PendingAlert(contact) } }
        setSending(sendingContacts.value.orEmpty() + recipients.map { it.uid })
        val recordedUrl = voice.value?.url
        fun deliver(location: Location?) {
            if (!cleared) {
                attempt.filter { it.attachment == null }.forEach {
                    it.attachment = AlertAttachment(location?.latitude, location?.longitude, recordedUrl)
                }
                deliverPending(attempt)
            }
        }
        if (attempt.any { it.attachment == null }) locate(::deliver) else deliver(null)
    }

    fun retryFailed(ids: Set<String> = lastRetryIds) {
        val current = contacts.value.orEmpty().associateBy { it.uid }
        sendTo(pendingAlerts.values.filter { it.id in ids }.mapNotNull { current[it.contact.uid] })
    }

    fun consumeOutcome(result: SendOutcome) {
        if (outcomes.firstOrNull() !== result) return
        outcomes.removeFirst()
        outcome.value = outcomes.firstOrNull()
    }

    private fun setSending(ids: Set<String>) {
        sendingContacts.value = ids
        sending.value = ids.isNotEmpty()
    }

    private fun deliverPending(attempt: List<PendingAlert>) {
        val failed = mutableListOf<PendingAlert>()
        val retryable = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        var accepted = 0
        var error: Throwable? = null
        attempt.forEach { pending ->
            val request = repository.sendAlert(pending.contact, pending.attachment!!, pending.id) { result ->
                if (!cleared && completed.add(pending.id)) {
                    alertRequests.remove(pending.id)
                    if (result.isSuccess) {
                        accepted++
                        pendingAlerts.remove(pending.contact.uid)
                    } else {
                        failed += pending
                        val original = result.exceptionOrNull()
                        val failure = if (pending.unconfirmed && original is AlertProblem)
                            AlertProblem(original.resource, original.retryable, true) else original
                        pending.unconfirmed = pending.unconfirmed || (failure as? AlertProblem)?.unconfirmed != false
                        if (error == null || (failure as? AlertProblem)?.unconfirmed == true) error = failure
                        if ((failure as? AlertProblem)?.retryable != false) retryable += pending.id
                        if ((failure as? AlertProblem)?.retryable == false && !pending.unconfirmed) pendingAlerts.remove(pending.contact.uid)
                    }
                    setSending(sendingContacts.value.orEmpty() - pending.contact.uid)
                    if (completed.size == attempt.size) {
                        val usedVoice = attempt.first().attachment?.voiceUrl
                        if (failed.isEmpty() && voice.value?.url == usedVoice &&
                            pendingAlerts.values.none { it.attachment?.voiceUrl == usedVoice }) clearVoice()
                        val retryIds = failed.filter { pendingAlerts[it.contact.uid] === it && it.id in retryable }.map { it.id }.toSet()
                        lastRetryIds = retryIds
                        outcomes.addLast(SendOutcome(accepted, attempt.size, error, retryIds))
                        outcome.value = outcomes.firstOrNull()
                    }
                }
            }
            if (pending.id !in completed) alertRequests[pending.id] = request
        }
    }

    private fun locate(done: (Location?) -> Unit) {
        val app = getApplication<Application>()
        val allowed = app.getSharedPreferences(LocationDisclosureActivity.PREFS_NAME, 0).getBoolean("location_disclosure_accepted", false)
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!allowed || (!fine && !coarse)) return done(null)
        val completed = AtomicBoolean(false)
        val cancellation = CancellationTokenSource().also { locationCancellations += it }
        var cached: Location? = null
        val timeout = Runnable { if (completed.compareAndSet(false, true)) { locationCancellations -= cancellation; cancellation.cancel(); done(cached) } }
        fun deliver(location: Location?) {
            if (completed.compareAndSet(false, true)) { handler.removeCallbacks(timeout); locationCancellations -= cancellation; cancellation.cancel(); done(location ?: cached) }
        }
        handler.postDelayed(timeout, 8_000)
        try {
            val client = LocationServices.getFusedLocationProviderClient(app)
            client.lastLocation.addOnSuccessListener { last ->
                if (last != null && (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000 < 10 * 60_000) cached = last
            }
            client.getCurrentLocation(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                .addOnSuccessListener { deliver(it) }.addOnFailureListener { deliver(null) }
        } catch (_: SecurityException) { deliver(null) }
    }

    override fun onCleared() {
        cleared = true
        voiceGeneration++
        subscriptions.forEach { it.remove() }
        historySubscription?.remove()
        recorder?.release()
        alertRequests.values.forEach { it.remove() }
        locationCancellations.toList().forEach { it.cancel() }
        locationCancellations.clear()
        handler.removeCallbacksAndMessages(null)
    }
}
