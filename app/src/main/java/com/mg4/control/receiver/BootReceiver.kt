package com.mg4.control.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.mg4.control.debug.AppLogger
import com.mg4.control.service.MG4ControlService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MG4_BOOT"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return

        AppLogger.i(TAG, "onReceive: $action — démarrage MG4ControlService")

        val serviceIntent = Intent(context, MG4ControlService::class.java)

        // Essai immédiat
        val started = tryStart(context, serviceIntent)
        AppLogger.i(TAG, "startForegroundService → $started")

        // Retry à 3s et 8s au cas où le système n'est pas encore prêt
        if (!started) {
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({
                val ok = tryStart(context, serviceIntent)
                AppLogger.i(TAG, "retry 3s → $ok")
            }, 3_000)
            h.postDelayed({
                val ok = tryStart(context, serviceIntent)
                AppLogger.i(TAG, "retry 8s → $ok")
            }, 8_000)
        }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startForegroundService(intent)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "startForegroundService error: ${e.message}")
            try { context.startService(intent); true } catch (_: Exception) { false }
        }
    }
}
