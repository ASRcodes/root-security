package com.asrcodes.dontmissmom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopSirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) { AlertDelivery.dismiss(context) }
}
