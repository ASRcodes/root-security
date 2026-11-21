package com.asrcodes.dontmissmom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(private val map: (EmergencyHistory) -> Unit, private val play: (EmergencyHistory) -> Unit) :
    ListAdapter<EmergencyHistory, HistoryAdapter.Holder>(Diff) {
    var playingId: String? = null
    var loadingVoice = false
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.historyName)
        val direction: TextView = view.findViewById(R.id.historyDirection)
        val phone: TextView = view.findViewById(R.id.historyPhone)
        val time: TextView = view.findViewById(R.id.historyTime)
        val status: TextView = view.findViewById(R.id.historyStatus)
        val location: TextView = view.findViewById(R.id.historyLocation)
        val map: MaterialButton = view.findViewById(R.id.historyMap)
        val voice: MaterialButton = view.findViewById(R.id.historyVoice)
        val voiceExpired: TextView = view.findViewById(R.id.historyVoiceExpired)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position); val context = holder.itemView.context
        val sent = item.direction == "sent"
        holder.direction.setText(if (sent) R.string.history_sent else R.string.history_received)
        holder.name.text = item.name.ifBlank { context.getString(R.string.unknown_sender) }
        holder.phone.text = item.phone.ifBlank { context.getString(R.string.sender_phone_unavailable) }
        holder.time.text = if (item.timestamp > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.timestamp)) else context.getString(R.string.history_time_unknown)
        holder.status.setText(when {
            sent && item.deliveryStatus == "unavailable" -> R.string.history_unavailable
            sent && item.deliveryStatus == "unconfirmed" -> R.string.history_unconfirmed
            sent -> R.string.history_sent_accepted
            item.sirenStatus == "timed_out" -> R.string.history_timeout
            item.sirenStatus == "stopped" -> R.string.history_stopped
            item.sirenStatus == "interrupted" -> R.string.history_interrupted
            item.sirenStatus == "ringing" && item.receivedAt > 0 && System.currentTimeMillis() - item.receivedAt < SirenPolicy.TIMEOUT_MS -> R.string.history_ringing
            item.sirenStatus == "received" -> R.string.history_received_on_device
            item.receivedAt > 0 -> R.string.history_no_response
            item.deliveryStatus == "unconfirmed" -> R.string.history_unconfirmed
            item.deliveryStatus == "unavailable" -> R.string.history_unavailable
            else -> R.string.history_saved
        })
        val hasLocation = IncomingAlert.validCoordinates(item.latitude, item.longitude)
        holder.location.text = if (hasLocation) String.format(Locale.getDefault(), "%.5f, %.5f", item.latitude, item.longitude) else context.getString(R.string.location_unavailable)
        holder.map.show(hasLocation); holder.map.setOnClickListener { map(item) }
        holder.voice.show(item.voiceUrl != null)
        holder.voiceExpired.show(!sent && item.voiceExpired && item.voiceUrl == null)
        holder.voice.setText(if (playingId == item.id) { if (loadingVoice) R.string.voice_loading else R.string.history_stop_voice } else R.string.history_play_voice)
        holder.voice.setOnClickListener { play(item) }
    }
    private object Diff : DiffUtil.ItemCallback<EmergencyHistory>() {
        override fun areItemsTheSame(old: EmergencyHistory, new: EmergencyHistory) = old.id == new.id
        override fun areContentsTheSame(old: EmergencyHistory, new: EmergencyHistory) = old == new
    }
}
