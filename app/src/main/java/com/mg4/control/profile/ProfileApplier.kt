package com.mg4.control.profile

import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.DrivingProfile
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object ProfileApplier {

    private const val TAG = "MG4_PROFILE"

    /**
     * Applique tous les paramètres de [profile] au véhicule de façon asynchrone.
     * Les opérations HVAC (siège/volant) sont bloquantes (polling jusqu'à 7s),
     * exécutées sur le dispatcher IO.
     */
    fun apply(profile: DrivingProfile, onComplete: ((Boolean) -> Unit)? = null) {
        AppLogger.i(TAG, "Application du profil : ${profile.name}")

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            var ok = true

            // Mode de conduite (rapide — binder call)
            val dmOk = MG4Hardware.setDriveMode(profile.driveMode)
            AppLogger.i(TAG, "  DriveMode=${profile.driveMode.label} → $dmOk")
            ok = ok && dmOk

            // Niveau de régénération (rapide — binder call)
            val rlOk = MG4Hardware.setRegenLevel(profile.regenLevel)
            AppLogger.i(TAG, "  RegenLevel=${profile.regenLevel.label} → $rlOk")
            ok = ok && rlOk

            // Volant + Sièges chauffants — uniquement SWI133 et SWI68 (SWI69/SWI131 n'ont pas ces équipements)
            if (FirmwareInfo.hasHeatFeatures()) {
                val shOk = MG4Hardware.setSteeringHeat(profile.steeringHeat)
                AppLogger.i(TAG, "  SteeringHeat=${profile.steeringHeat} → $shOk")
                val slOk = MG4Hardware.setSeatHeatLeft(profile.seatHeatLeft)
                AppLogger.i(TAG, "  SeatHeatLeft=${profile.seatHeatLeft} → $slOk")
                val srOk = MG4Hardware.setSeatHeatRight(profile.seatHeatRight)
                AppLogger.i(TAG, "  SeatHeatRight=${profile.seatHeatRight} → $srOk")
            }

            AppLogger.i(TAG, "Profil '${profile.name}' Katman1 terminé — ok=$ok")
            onComplete?.invoke(ok)

            // ADAS (Katman4) — appliqué dès que le service est prêt
            MG4Hardware.whenKatman4Ready {
                AppLogger.i(TAG, "  Application ADAS pour profil '${profile.name}'")
                if (FirmwareInfo.isVsmBased()) {
                    // ── SWI68/SWI69/SWI131/SWI165 ──────────────────────────────────────
                    //
                    // Le TSR est appliqué EN PREMIER : setTsrMode() bloque 400 ms en interne
                    // et restaure soundWarning depuis les préférences. On l'appelle en premier
                    // pour pouvoir ensuite écraser l'alerte sonore avec la valeur du profil.
                    val tsrOk = MG4Hardware.setTsrMode(profile.tsrEnabled)
                    AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")

                    // Alerte sonore — appliquée APRÈS le TSR pour écraser sa restauration interne
                    val swOk = MG4Hardware.setSoundWarning(profile.soundWarning)
                    AppLogger.i(TAG, "  SoundWarning=${profile.soundWarning} → $swOk")

                    val adOk = MG4Hardware.setAccTjaMode(profile.swi68AdasMode)
                    AppLogger.i(TAG, "  AdasMode=0x${profile.swi68AdasMode.toString(16)} → $adOk")
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)

                    // Économie d'énergie — tous firmwares VSM (SWI68/SWI69/SWI131/SWI165)
                    val esOk = MG4Hardware.setEnergySavingMode(profile.energySaving)
                    AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                } else {
                    // ── SWI133/UNKNOWN ──────────────────────────────────────────────────
                    //
                    // Même logique : activer le TSR ré-active OVERSPEED_ALARM et SPEED_LIMIT_TONE.
                    // setTsrMode() restaure depuis les préférences — on l'appelle en premier
                    // puis on écrase avec les valeurs du profil.
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val tsrOk = MG4Hardware.setTsrMode(profile.tsrEnabled)
                        AppLogger.i(TAG, "  TsrEnabled=${profile.tsrEnabled} → $tsrOk")
                    }

                    // Alertes vitesse — appliquées APRÈS le TSR
                    val oaOk = MG4Hardware.setOverspeedAlarm(profile.overspeedAlarm)
                    AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                    val stOk = MG4Hardware.setSpeedLimitTone(profile.speedLimitTone)
                    AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")

                    val adOk = MG4Hardware.setMixedIntelligentDrive(profile.adasMode)
                    AppLogger.i(TAG, "  AdasMode=${profile.adasMode} → $adOk")
                    applyAeb(profile.aebEnabled, profile.aebMode, profile.aebSensitivity)

                    // Économie d'énergie — SWI133 via VPM
                    if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI133) {
                        val esOk = MG4Hardware.setEnergySavingMode(profile.energySaving)
                        AppLogger.i(TAG, "  EnergySaving=${profile.energySaving} → $esOk")
                    }
                }
                // ELK — commun à tous les firmwares connus
                applyElk(profile.elkMode, profile.elkSensitivity)
            }
        }
    }

    /**
     * Applique les réglages ELK (assistant de sortie de voie) du profil — tous firmwares.
     * Si elkMode=0 (valeur par défaut — profil créé avant l'ajout de l'ELK),
     * on ne touche pas aux réglages ELK pour éviter une modification involontaire.
     */
    private fun applyElk(elkMode: Int, elkSensitivity: Int) {
        if (elkMode == 0) {
            AppLogger.i(TAG, "  ELK — valeurs par défaut, skip (évite modification involontaire)")
            return
        }
        val modeOk = MG4Hardware.setElkMode(elkMode)
        AppLogger.i(TAG, "  ElkMode=$elkMode → $modeOk")
        if (elkMode != MG4Hardware.ElkMode.OFF && elkSensitivity > 0) {
            val senOk = MG4Hardware.setElkSensitivity(elkSensitivity)
            AppLogger.i(TAG, "  ElkSensitivity=$elkSensitivity → $senOk")
        }
    }

    /**
     * Applique les réglages AEB du profil.
     * Si aebEnabled=false ET aebMode=1 ET aebSensitivity=0 (valeurs par défaut),
     * on ne touche pas à l'état AEB de la voiture pour éviter une désactivation involontaire.
     */
    private fun applyAeb(aebEnabled: Boolean, aebMode: Int, aebSensitivity: Int = 0) {
        val isDefault = !aebEnabled && aebMode == 1 && aebSensitivity == 0
        if (isDefault) {
            AppLogger.i(TAG, "  AEB — valeurs par défaut, skip (évite désactivation involontaire)")
            return
        }
        val aebOk = MG4Hardware.setAebEnabled(aebEnabled)
        AppLogger.i(TAG, "  AebEnabled=$aebEnabled → $aebOk")
        if (aebEnabled) {
            val aebModeOk = MG4Hardware.setAebMode(aebMode)
            AppLogger.i(TAG, "  AebMode=$aebMode → $aebModeOk")
            if (aebSensitivity > 0) {
                val senOk = MG4Hardware.setAebSensitivity(aebSensitivity)
                AppLogger.i(TAG, "  AebSensitivity=$aebSensitivity → $senOk")
            }
        }
    }
}
