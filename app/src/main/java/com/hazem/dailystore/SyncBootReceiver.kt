package com.hazem.dailystore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores an explicitly enabled relay after device reboot or app replacement. */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (SyncForegroundService.shouldRun(context)) {
            runCatching { SyncForegroundService.start(context.applicationContext) }
        }
    }
}
