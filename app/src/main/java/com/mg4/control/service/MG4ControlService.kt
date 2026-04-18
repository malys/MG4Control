package com.mg4.control.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mg4.control.MainActivity
import android.content.IntentFilter
import android.os.IBinder
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MG4ControlService : Service() {

    companion object {
        private const val TAG          = "MG4_SVC"
        private const val CHANNEL_ID   = "mg4_control_channel"
        private const val NOTIF_ID     = 1
        private const val PREFS_SHORTCUTS = "mg4_shortcuts"

        // Intent action broadcast par le système SAIC pour les touches physiques
        private const val HARDKEY_ACTION   = "com.saic.keyevent.hardkey.report"

        // Keycodes des boutons ★ du volant
        private const val KEYCODE_BTN1     = 17    // STAR_LEFT
        private const val KEYCODE_BTN2     = 286   // STAR_RIGHT
        private const val KEYCODE_BTN2_ALT = 18    // alias STAR_RIGHT (certains firmwares)

        /**
         * Flag one-shot : le profil n'est appliqué qu'une seule fois par session de processus.
         * Évite le double-apply quand MainActivity et BootReceiver démarrent le service.
         */
        @Volatile private var profileScheduled = false
    }

    // ── Hardkey receiver ─────────────────────────────────────────────────────

    private var hardkeyReceiver: BroadcastReceiver? = null

    // État par slot pour la détection d'appui long
    private val slotLongTriggered = mutableMapOf<String, Boolean>()

    // États des toggles en mémoire — réinitialisés à chaque démarrage du service (= redémarrage voiture)
    // Évite le bug du 1er appui : si on utilise SharedPrefs, l'état persisté peut ne pas correspondre
    // à l'état réel de la voiture après un redémarrage, causant un toggle dans le mauvais sens.
    private val toggleStates = mutableMapOf<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification())
        MG4Hardware.init(applicationContext)
        registerHardkeyReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        hardkeyReceiver?.let { unregisterReceiver(it) }
        hardkeyReceiver = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "onStartCommand")
        scheduleDefaultProfileOnce()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Enregistrement dynamique du receiver ─────────────────────────────────

    private fun registerHardkeyReceiver() {
        hardkeyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                handleHardkeyIntent(intent)
            }
        }
        registerReceiver(hardkeyReceiver, IntentFilter(HARDKEY_ACTION))
        AppLogger.i(TAG, "HardkeyReceiver enregistré → $HARDKEY_ACTION")
    }

    // ── Traitement d'un event hardkey ────────────────────────────────────────

    private fun handleHardkeyIntent(intent: Intent) {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // Raccourcis désactivés globalement → on laisse le launcher gérer
        if (!prefs.getBoolean("shortcut_enabled", false)) return

        // Lecture du keycode (plusieurs noms d'extra selon le firmware)
        val keycode = intent.getIntExtra("android.intent.extra.hardkey.keycode", -1)
            .takeIf { it >= 0 }
            ?: intent.getIntExtra("keycode", -1).takeIf { it >= 0 }
            ?: intent.getIntExtra("keyCode", -1).takeIf { it >= 0 }
            ?: return

        val isDown = intent.getBooleanExtra("android.intent.extra.hardkey.down", false)
                     || intent.getBooleanExtra("down", false)
        val isLong = intent.getBooleanExtra("android.intent.extra.hardkey.longpress", false)
                     || intent.getBooleanExtra("longpress", false)

        AppLogger.i(TAG, "HARDKEY keycode=$keycode down=$isDown long=$isLong")

        val slot = when (keycode) {
            KEYCODE_BTN1                   -> "btn1"
            KEYCODE_BTN2, KEYCODE_BTN2_ALT -> "btn2"
            else -> return
        }

        when {
            isDown && isLong -> {
                slotLongTriggered[slot] = true
                val pressKey = "${slot}_long"
                val action = ShortcutAction.fromId(prefs.getInt("shortcut_$pressKey", 0))
                if (action != ShortcutAction.NONE) executeToggle(action, pressKey)
            }
            isDown -> {
                slotLongTriggered[slot] = false
            }
            else -> {
                if (slotLongTriggered[slot] == true) {
                    slotLongTriggered[slot] = false
                    return
                }
                val pressKey = "${slot}_single"
                val action = ShortcutAction.fromId(prefs.getInt("shortcut_$pressKey", 0))
                if (action != ShortcutAction.NONE) executeToggle(action, pressKey)
            }
        }
    }

    // ── Exécution du toggle ──────────────────────────────────────────────────

    private fun executeToggle(action: ShortcutAction, pressKey: String = "") {
        val prefs = getSharedPreferences(PREFS_SHORTCUTS, MODE_PRIVATE)

        // APPLY_PROFILE : action directe — pas de toggle d'état, chaque pression applique le profil
        if (action == ShortcutAction.APPLY_PROFILE) {
            val profileId = prefs.getString("shortcut_${pressKey}_profile_id", null) ?: return
            CoroutineScope(Dispatchers.IO).launch {
                val profile = ProfileManager(applicationContext).getById(profileId)
                if (profile == null) {
                    prefs.edit().putInt("shortcut_$pressKey", ShortcutAction.NONE.id).apply()
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — profil $profileId introuvable, reset NONE")
                } else {
                    AppLogger.i(TAG, "SHORTCUT APPLY_PROFILE — application de '${profile.name}'")
                    ProfileApplier.apply(profile)
                }
            }
            return
        }

        // Pour tous les autres toggles : état en mémoire (réinitialisé au démarrage du service)
        // Évite le bug du 1er appui causé par un état SharedPrefs désynchronisé après redémarrage.
        val newState = !(toggleStates[action.name] ?: false)
        toggleStates[action.name] = newState

        AppLogger.i(TAG, "SHORTCUT ${action.name} → ${if (newState) "ON/A" else "OFF/B"}")

        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                ShortcutAction.ONE_PEDAL -> {
                    if (newState) {
                        MG4Hardware.setRegenLevel(RegenLevel.ONE_PEDAL)
                    } else {
                        val fallback = RegenLevel.fromValue(
                            prefs.getInt("shortcut_one_pedal_fallback", RegenLevel.HIGH.value)
                        )
                        MG4Hardware.setRegenLevel(fallback)
                    }
                }
                ShortcutAction.AEB_CYCLE -> {
                    val mode = if (newState)
                        prefs.getInt("shortcut_aeb_mode_a", AebMode.ALARM)
                    else
                        prefs.getInt("shortcut_aeb_mode_b", AebMode.ALARM_BRAKE)
                    MG4Hardware.setAebMode(mode)
                }
                ShortcutAction.SOUND_WARNING    -> MG4Hardware.setSoundWarning(newState)
                ShortcutAction.OVERSPEED_ALARM  -> MG4Hardware.setOverspeedAlarm(newState)
                ShortcutAction.SPEED_LIMIT_TONE -> MG4Hardware.setSpeedLimitTone(newState)
                ShortcutAction.ADAS_CYCLE -> {
                    val isVsmBased = FirmwareInfo.isVsmBased()
                    val modeA = prefs.getInt("shortcut_adas_mode_a",
                        if (isVsmBased) Swi68Mode.ACC else 3)
                    val modeB = prefs.getInt("shortcut_adas_mode_b",
                        if (isVsmBased) Swi68Mode.OFF else 0)
                    val mode  = if (newState) modeA else modeB
                    if (isVsmBased) MG4Hardware.setAccTjaMode(mode)
                    else            MG4Hardware.setMixedIntelligentDrive(mode)
                }
                ShortcutAction.ENERGY_SAVING_TOGGLE -> MG4Hardware.setEnergySavingMode(newState)
                ShortcutAction.TSR_TOGGLE           -> MG4Hardware.setTsrMode(newState)
                ShortcutAction.OPEN_APP -> {
                    val intent = Intent(this@MG4ControlService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
                ShortcutAction.OPEN_CUSTOM_APP -> {
                    val pkg = prefs.getString("shortcut_${pressKey}_custom_app", null)
                    if (pkg != null) {
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    }
                }
                else -> {}
            }
        }
    }

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
