package com.callforwarder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs.enabled(context) || !Prefs.isConfigured(context)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> BridgeService.send(context, BridgeService.ACTION_RING, number)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> BridgeService.send(context, BridgeService.ACTION_OFFHOOK, null)
            TelephonyManager.EXTRA_STATE_IDLE -> BridgeService.send(context, BridgeService.ACTION_IDLE, null)
        }
    }
}
