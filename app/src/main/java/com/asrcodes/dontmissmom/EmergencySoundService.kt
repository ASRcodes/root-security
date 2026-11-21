package com.asrcodes.dontmissmom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class EmergencySoundService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START_SIREN"
        const val ACTION_STOP = "ACTION_STOP_SIREN"
        const val NOTIFICATION_ID = AlertDelivery.NOTIFICATION_ID
        const val PREFS_NAME = "DontMissMomPrefs"
        @Volatile var isRunning = false
            private set
    }
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null
    private var originalVolume: Int? = null
    private var raisedVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentAlert: IncomingAlert? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timedOut = false
    private val timeout = Runnable {
        timedOut = true
        currentAlert?.let { AlertReceipt.update(it, "timed_out"); AlertDelivery.showHistoryNotification(this, it) }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val alert = IncomingAlert.fromIntent(intent)
        if (currentAlert?.id == alert.id) return START_NOT_STICKY
        currentAlert?.let { AlertReceipt.update(it, "interrupted") }
        currentAlert = alert
        timedOut = false
        try { startForeground(NOTIFICATION_ID, AlertDelivery.notification(this, alert)) }
        catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        isRunning = true
        (application as? org.suraksha.kavach.KavachApplication)?.dismissWarnings()
        handler.removeCallbacks(timeout)
        // A bounded partial wake lock also covers vibration-only mode, so deep sleep
        // cannot postpone Handler's uptime-based timeout. Process death stops playback.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:siren-timeout")
            .apply { acquire(SirenPolicy.TIMEOUT_MS + 10_000) }
        handler.postDelayed(timeout, SirenPolicy.TIMEOUT_MS)
        AlertReceipt.update(alert, "ringing")
        if (player == null) startAlert()
        return START_NOT_STICKY
    }

    private fun startAlert() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else { @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator) }
        try {
            val pattern = VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 800), 0)
            if (Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else { @Suppress("DEPRECATION") vibrator?.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()) }
        } catch (_: RuntimeException) { }
        if (!SirenSettings(this).enabled()) return
        val manager = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) stopSelf()
            }.build().also { runCatching { manager.requestAudioFocus(it) } }
        try {
            originalVolume = manager.getStreamVolume(AudioManager.STREAM_ALARM)
            raisedVolume = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            manager.setStreamVolume(AudioManager.STREAM_ALARM, raisedVolume!!, 0)
        } catch (_: SecurityException) { originalVolume = null }
        try {
            player = MediaPlayer()
            player?.apply {
                setAudioAttributes(attributes)
                setWakeMode(this@EmergencySoundService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@EmergencySoundService, Uri.parse("android.resource://" + packageName + "/" + R.raw.siren))
                isLooping = true
                setOnErrorListener { _, _, _ -> stopSelf(); true }
                prepare()
                start()
            }
        } catch (_: Exception) { releaseAudio() }
    }

    private fun releaseAudio() {
        runCatching { player?.release() }
        player = null
        val manager = getSystemService(AudioManager::class.java)
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }; focus = null
        originalVolume?.let { previous ->
            runCatching { if (manager.getStreamVolume(AudioManager.STREAM_ALARM) == raisedVolume) manager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
        }
        originalVolume = null
    }
    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        releaseAudio()
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (!timedOut) currentAlert?.let { AlertReceipt.update(it, "stopped") }
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
