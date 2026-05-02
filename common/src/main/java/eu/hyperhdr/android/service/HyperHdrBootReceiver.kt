package eu.hyperhdr.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HyperHdrBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Start the foreground service in IDLE; capture requires user consent — see spec §7.6.
        context.startForegroundService(Intent(context, HyperHdrCaptureService::class.java))
    }
}
