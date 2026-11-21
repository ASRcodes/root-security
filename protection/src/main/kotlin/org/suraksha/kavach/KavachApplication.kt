package org.suraksha.kavach

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import org.suraksha.kavach.audio.WarningAudio
import org.suraksha.kavach.family.*
import org.suraksha.kavach.files.*
import org.suraksha.kavach.notifications.WarningOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.suraksha.core.*
import org.suraksha.kavach.data.*
import org.suraksha.kavach.notifications.AlertDispatcher
import org.suraksha.kavach.calls.CallAlerts
import org.suraksha.kavach.reputation.*

enum class LoadState { LOADING, READY, FAILED }
enum class RefreshState { IDLE, WORKING, DONE, FAILED }
data class MessageOutcome(val results: List<ScanResult> = emptyList(), val available: Boolean = true)

open class KavachApplication : Application() {
    open val secureWindows: Boolean = true
    protected open fun createSmsSender(): SmsSender = AndroidSmsSender(this)
    protected open fun createOnlineChecker(): OnlineUrlChecker = OnlineUrlChecker(this, secrets, scope)
    /** The host can give its active emergency screen/siren priority over scam interruptions. */
    protected open fun intrusiveWarningsAllowed(): Boolean = true
    /** Shared by call banners and message warnings; the host's active SOS always has priority. */
    fun canShowIntrusiveWarnings(): Boolean = !resetting && intrusiveWarningsAllowed()
    private var foreground: WeakReference<Activity>? = null
    lateinit var files: FileScanner; private set
    lateinit var watcher: FolderWatcher; private set
    lateinit var familyCode: FamilyCode; private set
    lateinit var guardian: GuardianManager; private set
    lateinit var audio: WarningAudio; private set
    lateinit var overlays: WarningOverlay; private set
    lateinit var online: OnlineUrlChecker; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val demoEnabled = MutableStateFlow(false)
    val listenerConnected = MutableStateFlow(false)
    val modelState = MutableStateFlow(LoadState.LOADING)
    val storageState = MutableStateFlow(LoadState.LOADING)
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val refreshState = MutableStateFlow(RefreshState.IDLE)
    val resetInProgress = MutableStateFlow(false)
    lateinit var settings: AppSettings; private set
    lateinit var secrets: SecretStore; private set
    lateinit var alerts: AlertDispatcher; private set
    private lateinit var store: ModelStore
    private lateinit var loadedModels: Deferred<Unit>
    private lateinit var loadedDatabase: Deferred<HistoryDatabase?>
    @Volatile private var pipeline: MessagePipeline? = null
    private val persistence = Mutex()
    private val refreshLock = Mutex()
    private val operations = Mutex()
    private val fileOperations = Mutex()
    private val resetLock = Mutex()
    private val generation = AtomicLong()
    internal val protectionGeneration: Long get() = generation.get()
    @Volatile private var resetting = false

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        secrets = SecretStore(this)
        online = createOnlineChecker()
        files = FileScanner(this)
        familyCode = FamilyCode(secrets)
        watcher = FolderWatcher(this)
        scope.launch(Dispatchers.IO) { watcher.refresh() }
        guardian = GuardianManager(this, secrets, createSmsSender())
        audio = WarningAudio(this)
        overlays = WarningOverlay(this)
        scope.launch(Dispatchers.IO) { guardian.refresh() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity); scope.launch(Dispatchers.IO) { guardian.refresh() } }
            override fun onActivityPaused(activity: Activity) {
                if (foreground?.get() === activity) foreground = null
                if (activity is MainActivity) audio.stop()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        store = ModelStore(this)
        alerts = AlertDispatcher(this)
        alerts.createChannels()
        retryModels()
        loadedDatabase = scope.async(Dispatchers.IO) {
            try {
                HistoryDatabase.open(this@KavachApplication, secrets).also { storageState.value = LoadState.READY }
            } catch (e: CancellationException) { throw e }
            catch (_: LinkageError) { storageState.value = LoadState.FAILED; null }
            catch (_: Exception) { storageState.value = LoadState.FAILED; null }
        }
        scope.launch {
            loadedDatabase.await()?.let { db ->
                try {
                    pruneHistory()
                    db.history().observe().collect { history.value = it }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { storageState.value = LoadState.FAILED }
            }
        }
        RetentionJob.schedule(this)
    }

    fun retryModels() {
        if (::loadedModels.isInitialized && loadedModels.isActive) return
        modelState.value = LoadState.LOADING
        loadedModels = scope.async(Dispatchers.IO) {
            try {
                pipeline = MessagePipeline(store.load())
                modelState.value = LoadState.READY
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { modelState.value = LoadState.FAILED }
        }
    }

    suspend fun processMessage(text: String, source: String, simulate: Boolean = false,
                               token: Long = generation.get()): MessageOutcome {
        return operations.withLock {
            if (resetting || token != generation.get()) return@withLock MessageOutcome(available = false)
            scanMessage(text, source, simulate, token)
        }
    }

    private suspend fun scanMessage(text: String, source: String, simulate: Boolean, token: Long): MessageOutcome {
        if (!simulate && !settings.state.value.protectionEnabled) return MessageOutcome()
        loadedModels.await()
        if (!simulate && !settings.state.value.protectionEnabled) return MessageOutcome()
        val selected = pipeline
        if (selected == null) return MessageOutcome(available = false)
        val results = try {
            // Reserved .example inputs exercise the same local engine without sending fictional
            // destinations to a provider. Their TEST_INPUT label never changes the verdict.
            val checks = online.checkAll(UrlExtractor.extract(text).filterNot(ExampleLinks::isReservedExample))
            withContext(Dispatchers.Default) {
                selected.scan(text, source, deduplicate = !simulate) { url, local ->
                    val check = checks[url]?.takeIf(online::isCurrent)
                    when (check?.status) {
                        ReputationStatus.THREAT -> ReputationPolicy.expire(local.copy(
                            verdict = ReputationPolicy.verifiedThreat(local.verdict, ReputationPolicy.isProviderTestUrl(url)),
                            timestamp = check.checkedAt), System.currentTimeMillis())
                        ReputationStatus.NO_MATCH -> local.copy(verdict = ReputationPolicy.noMatch(local.verdict))
                        else -> local.copy(verdict = ReputationPolicy.notChecked(local.verdict))
                    }
                }
            }
        }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return MessageOutcome(available = false) }
        if (resetting || token != generation.get() || (!simulate && !settings.state.value.protectionEnabled)) return MessageOutcome()
        results.filter { it.verdict.level != Level.SAFE }.maxByOrNull { it.verdict.level.ordinal }?.let {
            handleWarning(it, manual = simulate, token = token)
        }
        saveResults(results, token)
        return MessageOutcome(results)
    }

    suspend fun processFile(uri: Uri, manual: Boolean = true): ScanResult {
        val token = generation.get()
        return fileOperations.withLock {
            if (resetting || token != generation.get()) return@withLock FileScanner.unreadable()
            val result = files.scan(uri)
            if (!resetting && token == generation.get() && (manual || settings.state.value.protectionEnabled)) {
                if (result.verdict.level != Level.SAFE) handleWarning(result, manual, token)
                saveResults(listOf(result), token)
            }
            result
        }
    }

    private suspend fun handleWarning(result: ScanResult, manual: Boolean, token: Long) {
        if (resetting || token != generation.get()) return
        // A pasted link or alert preview must never contact a family member automatically.
        if (!manual) scope.launch(Dispatchers.IO) {
            if (resetting || token != generation.get()) return@launch
            try { guardian.onDanger(result) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { guardian.markFailure() }
        }
        if (!intrusiveWarningsAllowed()) {
            alerts.post(result, allowFullScreen = false, silent = true)
            return
        }
        if (manual) {
            return
        }
        withContext(Dispatchers.Main.immediate) {
            if (resetting || token != generation.get()) return@withContext
            if (!intrusiveWarningsAllowed()) {
                alerts.post(result, allowFullScreen = false, silent = true)
                return@withContext
            }
            if (result.verdict.level == Level.DANGEROUS) {
                val activity = foreground?.get()
                if (activity != null && !activity.isFinishing) {
                    runCatching { activity.startActivity(AlertActivity.intent(activity, result)) }.onFailure { alerts.post(result) }
                }
                else {
                    val overlayVisible = overlays.show(result)
                    alerts.post(result, allowFullScreen = !overlayVisible)
                }
            } else alerts.post(result, allowFullScreen = false, silent = true)
        }
    }

    fun dismissWarnings() {
        scope.launch(Dispatchers.Main.immediate) { audio.stop(); overlays.dismiss(); alerts.cancel(); CallAlerts.dismissAll(this@KavachApplication) }
    }
    fun speakWarning() = playWarning(false)
    fun previewWarning() = playWarning(true)
    /** Explicit UI rehearsal, isolated from the scanner, history and guardian SMS. */
    fun previewTestAlert() {
        if (!BuildConfig.DEBUG || resetting || !intrusiveWarningsAllowed()) return
        scope.launch(Dispatchers.Main.immediate) {
            val preview = ScanResult("", Verdict(Level.DANGEROUS, 1.0, setOf(ReasonCode.DEMO_ONLY)), System.currentTimeMillis())
            val activity = foreground?.get()
            if (activity != null && !activity.isFinishing) activity.startActivity(AlertActivity.intent(activity, preview))
        }
    }
    private fun playWarning(explicit: Boolean) {
        if (explicit || settings.state.value.voiceEnabled) scope.launch(Dispatchers.Main.immediate) {
            if (intrusiveWarningsAllowed()) audio.play(settings.state.value.language)
        }
    }
    fun callGuardian(context: Context) {
        scope.launch(Dispatchers.IO) {
            val number = runCatching { guardian.number() }.getOrNull()
            withContext(Dispatchers.Main) {
                val destination = if (number != null) Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                    else Intent(context, MainActivity::class.java).putExtra("page", "GUARDIAN")
                destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(destination) }.onFailure {
                    Toast.makeText(context, AppSettings.localized(context).getString(R.string.sk_dial_unavailable), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun saveResults(results: List<ScanResult>, token: Long = generation.get()) {
        if (results.isEmpty() || resetting || token != generation.get()) return
        // Only these metadata objects survive asynchronous dispatch.
        val entries = results.groupBy { it.domain }.values.map { group ->
            HistoryEntry.from(group.maxBy { it.verdict.level.ordinal })
        }
        scope.launch(Dispatchers.IO) {
            val db = loadedDatabase.await() ?: return@launch
            try {
                persistence.withLock {
                    if (resetting || token != generation.get()) return@withLock
                    db.history().insert(entries)
                    db.history().deleteBefore(cutoff())
                    db.history().trim()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageState.value = LoadState.FAILED }
        }
    }

    private fun cutoff() = System.currentTimeMillis() - settings.state.value.retentionDays * 86_400_000L
    suspend fun pruneHistory() {
        val db = loadedDatabase.await() ?: return
        try { persistence.withLock { db.history().deleteBefore(cutoff()); db.history().trim() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { storageState.value = LoadState.FAILED }
    }
    internal suspend fun databaseForTests(): HistoryDatabase? = loadedDatabase.await()

    fun refreshBlocklist() {
        // Downloads remain disabled; the bundled historical list is only one local risk hint.
        refreshState.value = RefreshState.IDLE
    }

    /** Resets only this feature. The host account, emergency data and settings are never touched. */
    suspend fun clearProtectionData(): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        resetLock.withLock {
            resetting = true
            resetInProgress.value = true
            generation.incrementAndGet()
            try {
                settings.setProtection(false)
                settings.setCallShield(false)
                settings.setEnhanced(false)
                settings.setOverlay(false)
                online.clearKey()
                guardian.reset()
                watcher.disable()
                withContext(Dispatchers.Main) { audio.stop(); overlays.dismiss(); alerts.cancel(); CallAlerts.dismissAll(this@KavachApplication) }
                // Await active scans/refreshes and invalidate queued writes before deleting metadata.
                operations.withLock {
                    fileOperations.withLock {
                    refreshLock.withLock {
                        loadedModels.await()
                        persistence.withLock {
                            val database = loadedDatabase.await()
                            if (database != null) database.history().clear()
                            else {
                                check(deleteDatabase(HistoryDatabase.NAME) || !getDatabasePath(HistoryDatabase.NAME).exists())
                                val replacement = HistoryDatabase.open(this@KavachApplication, secrets)
                                loadedDatabase = CompletableDeferred(replacement)
                                scope.launch { replacement.history().observe().collect { history.value = it } }
                            }
                            history.value = emptyList()
                            storageState.value = LoadState.READY
                        }
                        familyCode.reset()
                        files.clearTemporaryFiles()
                        store.clearDownloadedList()
                        pipeline = MessagePipeline(store.load())
                        modelState.value = LoadState.READY
                        demoEnabled.value = false
                        settings.reset()
                        refreshState.value = RefreshState.IDLE
                    }
                    }
                }
                withContext(Dispatchers.Main) { audio.stop(); overlays.dismiss(); alerts.cancel(); CallAlerts.dismissAll(this@KavachApplication) }
                true
            } catch (_: Exception) {
                // Leave protection paused when a partial reset needs a retry.
                settings.setProtection(false)
                settings.setCallShield(false)
                false
            } finally {
                resetting = false
                resetInProgress.value = false
            }
        }
    }

    @Deprecated("Use the explicit previewTestAlert action; scans always use real detection")
    fun setDemo(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        demoEnabled.value = false
    }
}
