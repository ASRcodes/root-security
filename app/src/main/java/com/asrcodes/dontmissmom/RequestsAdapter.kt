package com.asrcodes.dontmissmom

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class RequestsAdapter(private val accept: (RequestItem) -> Unit, private val decline: (RequestItem) -> Unit,
    private val report: (RequestItem) -> Unit) : ListAdapter<RequestItem, RequestsAdapter.VH>(Diff) {
    var busyIds: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val phone: TextView = view.findViewById(R.id.tvPhone)
        val accept: MaterialButton = view.findViewById(R.id.btnAccept)
        val decline: MaterialButton = view.findViewById(R.id.btnDecline)
        val report: MaterialButton = view.findViewById(R.id.btnReport)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val request = getItem(position)
        holder.name.text = request.fromUsername.ifBlank { holder.itemView.context.getString(R.string.unknown_sender) }
        holder.phone.text = request.fromPhone
        listOf(holder.accept, holder.decline, holder.report).forEach { it.isEnabled = request.id !in busyIds }
        holder.accept.setOnClickListener { accept(request) }
        holder.decline.setOnClickListener { decline(request) }
        holder.report.setOnClickListener { report(request) }
    }
    private object Diff : DiffUtil.ItemCallback<RequestItem>() {
        override fun areItemsTheSame(oldItem: RequestItem, newItem: RequestItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RequestItem, newItem: RequestItem) = oldItem == newItem
    }
}
