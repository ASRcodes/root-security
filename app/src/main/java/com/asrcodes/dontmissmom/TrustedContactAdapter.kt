package com.asrcodes.dontmissmom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class TrustedContactAdapter(
    items: MutableList<TrustedContact>,
    private val onEmergencyClick: (TrustedContact) -> Unit,
    private val onLongPress: (TrustedContact) -> Unit
) : ListAdapter<TrustedContact, TrustedContactAdapter.VH>(Diff) {
    var sendingContacts = emptySet<String>()
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    var preparingVoice = false
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }
    init { submitList(items.toList()) }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvContactName)
        val phone: TextView = view.findViewById(R.id.tvContactPhone)
        val avatar: TextView = view.findViewById(R.id.tvAvatar)
        val alert: MaterialButton = view.findViewById(R.id.btnEmergencyItem)
        val options: View = view.findViewById(R.id.btnContactOptions)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trusted_contact, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = getItem(position)
        val context = holder.itemView.context
        val name = contact.username.ifBlank { context.getString(R.string.unknown_sender) }
        holder.name.text = name
        holder.phone.text = contact.phone
        holder.avatar.text = name.take(1).uppercase()
        val sending = contact.uid in sendingContacts
        holder.alert.isEnabled = !contact.blocked && !sending && !preparingVoice
        holder.alert.text = when {
            contact.blocked -> context.getString(R.string.blocked)
            sending -> context.getString(R.string.sending_sos)
            else -> context.getString(R.string.send_to, name.substringBefore(" "))
        }
        holder.alert.setOnClickListener { onEmergencyClick(contact) }
        holder.options.contentDescription = context.getString(R.string.contact_options, name)
        holder.options.setOnClickListener { onLongPress(contact) }
        holder.itemView.setOnLongClickListener { onLongPress(contact); true }
    }
    fun setData(items: List<TrustedContact>) { submitList(items.toList()) }
    private object Diff : DiffUtil.ItemCallback<TrustedContact>() {
        override fun areItemsTheSame(oldItem: TrustedContact, newItem: TrustedContact) = oldItem.uid == newItem.uid
        override fun areContentsTheSame(oldItem: TrustedContact, newItem: TrustedContact) = oldItem == newItem
    }
}
