package com.asrcodes.dontmissmom

import android.content.Intent
import android.location.Geocoder
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import java.util.Locale

class AlertPopupActivity : BaseActivity() {
    private var player: MediaPlayer? = null
    private var alert: IncomingAlert? = null
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alert_popup)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = dismiss() })
        render(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render(intent) }

    private fun render(intent: Intent) {
        generation++
        releasePlayer()
        val current = IncomingAlert.fromIntent(intent).also { alert = it }
        val name = current.name.ifBlank { getString(R.string.unknown_sender) }
        findViewById<TextView>(R.id.tvSenderName).text = name
        findViewById<TextView>(R.id.tvSenderAvatar).text = name.trim().take(1).uppercase(Locale.getDefault())
        findViewById<TextView>(R.id.tvSenderPhone).text = current.phone.ifBlank { getString(R.string.sender_phone_unavailable) }
        val locationView = findViewById<TextView>(R.id.tvLocationCoordinates)
        val hasLocation = IncomingAlert.validCoordinates(current.latitude, current.longitude)
        findViewById<View>(R.id.btnMap).show(hasLocation)
        if (hasLocation) {
            val lat = current.latitude!!; val lng = current.longitude!!
            locationView.text = String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng)
            resolveAddress(lat, lng, generation)
            findViewById<View>(R.id.btnMap).setOnClickListener {
                stopSound()
                val uri = "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng
                openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            }
        } else locationView.setText(R.string.location_unavailable)
        findViewById<View>(R.id.alertVoiceCard).show(current.voiceUrl != null)
        findViewById<MaterialButton>(R.id.btnStopAndListen).apply {
            isEnabled = true
            setText(R.string.listen_voice)
            setOnClickListener { current.voiceUrl?.let { stopSound(); playVoice(it) } }
        }
        findViewById<MaterialButton>(R.id.btnCallBack).apply {
            isEnabled = current.phone.isNotBlank()
            setOnClickListener {
                stopSound()
                openExternal(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", current.phone, null)))
            }
        }
        findViewById<View>(R.id.btnDismiss).setOnClickListener { dismiss() }
        findViewById<NestedScrollView>(R.id.alertDetailsScroll).scrollTo(0, 0)
    }

    private fun resolveAddress(lat: Double, lng: Double, version: Int) {
        if (!Geocoder.isPresent()) return
        Thread {
            try {
                val geocoder = Geocoder(applicationContext, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= 33) {
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) { addresses.firstOrNull()?.getAddressLine(0)?.let { showAddress(it, version) } }
                        override fun onError(errorMessage: String?) { }
                    })
                } else {
                    @Suppress("DEPRECATION")
                    val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
                    if (address != null) showAddress(address, version)
                }
            } catch (_: Exception) { }
        }.start()
    }
    private fun showAddress(address: String, version: Int) { runOnUiThread {
        if (!isDestroyed && version == generation) findViewById<TextView>(R.id.tvLocationCoordinates).text = address
    } }

    private fun playVoice(url: String) {
        releasePlayer()
        val button = findViewById<MaterialButton>(R.id.btnStopAndListen)
        button.isEnabled = false; button.setText(R.string.voice_loading)
        val version = generation
        val timeout = Runnable { if (version == generation) playbackFailed() }
        handler.postDelayed(timeout, 15_000)
        try {
            player = MediaPlayer()
            player?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(url)
                setOnPreparedListener {
                    handler.removeCallbacks(timeout)
                    if (version == generation && !isDestroyed) { it.start(); button.setText(R.string.voice_playing) }
                }
                setOnCompletionListener { releasePlayer(); button.isEnabled = true; button.setText(R.string.voice_replay) }
                setOnErrorListener { _, _, _ -> playbackFailed(); true }
                prepareAsync()
            }
        } catch (_: Exception) { playbackFailed() }
    }
    private fun playbackFailed() {
        releasePlayer()
        if (!isDestroyed) {
            findViewById<MaterialButton>(R.id.btnStopAndListen).apply { isEnabled = true; setText(R.string.voice_replay) }
            message(R.string.voice_playback_error)
        }
    }
    private fun releasePlayer() { handler.removeCallbacksAndMessages(null); runCatching { player?.release() }; player = null }
    private fun stopSound() { AlertDelivery.dismiss(this); releasePlayer() }
    private fun dismiss() { stopSound(); finish() }
    override fun onStop() {
        releasePlayer()
        findViewById<MaterialButton>(R.id.btnStopAndListen).apply { isEnabled = true; setText(R.string.listen_voice) }
        super.onStop()
    }
    override fun onDestroy() { generation++; releasePlayer(); if (isFinishing) AlertDelivery.dismiss(this); super.onDestroy() }
}
