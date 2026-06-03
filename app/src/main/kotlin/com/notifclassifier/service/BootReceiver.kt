package com.notifclassifier.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the listener service after:
 *  - Device boot (BOOT_COMPLETED)
 *  - MIUI-specific quick-boot
 *  - Internal self-restart request (when the service is killed by the OS)
 */
class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.notifclassifier.RESTART_SERVICE" -> {
                Log.d(TAG, "Starting NotifListenerService via BootReceiver")
                val serviceIntent = Intent(context, NotifListenerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
