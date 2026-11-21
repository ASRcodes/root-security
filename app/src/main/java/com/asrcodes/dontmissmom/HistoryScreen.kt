package com.asrcodes.dontmissmom

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryScreen(private val activity: MainActivity, private val model: MainViewModel) {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = HistoryAdapter({ item ->
        activity.openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${item.latitude},${item.longitude}")))
    }, { play(it) })
    init {
        activity.findViewById<RecyclerView>(R.id.recyclerHistory).apply {
            layoutManager = LinearLayoutManager(activity); adapter = this@HistoryScreen.adapter
        }
        activity.findViewById<View>(R.id.btnRetryHistory).setOnClickListener { model.reloadHistory() }
        activity.findViewById<View>(R.id.btnMoreHistory).setOnClickListener { model.loadMoreHistory() }
        model.history.observe(activity) { items ->
            if (adapter.playingId != null && items.none { it.id == adapter.playingId && it.voiceUrl != null }) stop()
            adapter.submitList(items); render()
        }
        model.historyLoading.observe(activity) { render() }; model.historyError.observe(activity) { render() }
        model.historyHasMore.observe(activity) { render() }
    }
    private fun render() {
        val loading = model.historyLoading.value == true; val error = model.historyError.value == true
        activity.findViewById<View>(R.id.historyProgress).show(loading)
        activity.findViewById<View>(R.id.historyState).show(!loading && (error || model.history.value.isNullOrEmpty()))
        activity.findViewById<View>(R.id.recyclerHistory).show(!model.history.value.isNullOrEmpty())
        activity.findViewById<View>(R.id.btnRetryHistory).show(error)
        activity.findViewById<View>(R.id.btnMoreHistory).show(!loading && !error && model.historyHasMore.value == true)
        activity.findViewById<TextView>(R.id.historyStateTitle).setText(if (error) R.string.history_error else R.string.history_empty_title)
        activity.findViewById<TextView>(R.id.historyStateBody).setText(if (error) R.string.connection_error else R.string.history_empty_body)
    }
    private fun play(item: EmergencyHistory) {
        val wasPlaying = adapter.playingId == item.id
        stop()
        if (wasPlaying) return
        val url = item.voiceUrl ?: return
        adapter.playingId = item.id; adapter.loadingVoice = true; adapter.notifyDataSetChanged()
        val timeout = Runnable { failed() }; handler.postDelayed(timeout, 15_000)
        try {
            player = MediaPlayer()
            player?.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(url)
                setOnPreparedListener {
                    if (player === it) { handler.removeCallbacks(timeout); it.start(); adapter.loadingVoice = false; adapter.notifyDataSetChanged() }
                }
                setOnCompletionListener { if (player === it) stop() }
                setOnErrorListener { media, _, _ -> if (player === media) failed(); true }
                prepareAsync()
            }
        } catch (_: Exception) { failed() }
    }
    private fun failed() { stop(); activity.message(R.string.voice_playback_error) }
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }; player = null
        adapter.playingId = null; adapter.loadingVoice = false; adapter.notifyDataSetChanged()
    }
}
