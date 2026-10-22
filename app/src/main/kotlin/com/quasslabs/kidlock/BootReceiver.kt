package com.quasslabs.kidlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies the saved policy after a reboot. Device Owner policy persists on its own; this is a safety net. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            KidLock.applyPolicies(context)
        }
    }
}
