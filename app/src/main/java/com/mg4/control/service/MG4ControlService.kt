package com.mg4.control.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager

class MG4ControlService : Service() {

    companion object {
        private const val TAG = "MG4_SVC"
        private const val CHANNEL_ID = "mg4_control_channel"
        private const val NOTIF_ID = 1

        /**
         * Flag one-shot : le profil n'est appliqué qu'une seule fois par session de processus.
         * Évite le double-apply quand MainActivity et BootReceiver démarrent le service.
         */
        @Volatile private var profileScheduled = false
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification())
        MG4Hardware.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand")
        scheduleDefaultProfileOnce()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Planifie l'application du profil par défaut une seule fois.
     * Attend que Katman1 soit opérationnel avant d'envoyer les commandes au véhicule.
     */
    private fun scheduleDefaultProfileOnce() {
        if (profileScheduled) {
            AppLogger.i(TAG, "Profil déjà planifié — skip")
            return
        }
        profileScheduled = true

        val prefs = getSharedPreferences("mg4_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_apply_profile", true)) {
            AppLogger.i(TAG, "auto_apply_profile désactivé — skip")
            return
        }

        val profile = ProfileManager(applicationContext).getDefaultProfile()
        if (profile == null) {
            AppLogger.i(TAG, "Aucun profil par défaut défini — skip")
            return
        }

        AppLogger.i(TAG, "Profil '${profile.name}' en attente hardware Katman1...")
        MG4Hardware.whenKatman1Ready {
            AppLogger.i(TAG, "Hardware prêt → application du profil '${profile.name}'")
            ProfileApplier.apply(profile) { ok ->
                AppLogger.i(TAG, "Profil '${profile.name}' appliqué — ok=$ok")
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MG4 Control", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MG4 Control")
            .setContentText("Service actif")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()
    }
}
