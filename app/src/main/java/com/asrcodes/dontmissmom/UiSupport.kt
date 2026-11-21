package com.asrcodes.dontmissmom

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun BaseActivity.message(resource: Int) {
    val confirmed = resource in setOf(R.string.contact_updated, R.string.contact_removed,
        R.string.request_sent, R.string.request_accepted, R.string.request_declined, R.string.report_sent)
    Snackbar.make(findViewById(android.R.id.content), resource, Snackbar.LENGTH_LONG).apply {
        if (confirmed) {
            setBackgroundTint(ContextCompat.getColor(context, R.color.success_soft))
            setTextColor(ContextCompat.getColor(context, R.color.success_ink))
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).statusTone(true)
        }
    }.show()
}

/** Pair semantic color with a visible symbol; the status text remains the accessible label. */
fun TextView.statusTone(confirmed: Boolean) {
    setTextColor(ContextCompat.getColor(context, if (confirmed) R.color.success_ink else R.color.warning_ink))
    setCompoundDrawablesRelativeWithIntrinsicBounds(if (confirmed) R.drawable.ic_check_circle else R.drawable.ic_warning, 0, 0, 0)
    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
}
fun BaseActivity.failure(error: Throwable?) {
    var cause = error
    while (cause != null && cause !is AppProblem && cause.cause !== cause) cause = cause.cause
    message((cause as? AppProblem)?.resource ?: R.string.operation_error)
}
fun BaseActivity.openExternal(intent: Intent) {
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { message(R.string.no_handler) }
}
fun View.show(visible: Boolean) { visibility = if (visible) View.VISIBLE else View.GONE }
