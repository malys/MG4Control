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

            // Volant chauffant (HVAC — peut bloquer jusqu'à 2s)
            val shOk = MG4Hardware.setSteeringHeat(profile.steeringHeat)
            AppLogger.i(TAG, "  SteeringHeat=${profile.steeringHeat} → $shOk")

            // Siège gauche (HVAC — peut bloquer jusqu'à 7s)
            val slOk = MG4Hardware.setSeatHeatLeft(profile.seatHeatLeft)
            AppLogger.i(TAG, "  SeatHeatLeft=${profile.seatHeatLeft} → $slOk")

            // Siège droit (HVAC — peut bloquer jusqu'à 7s)
            val srOk = MG4Hardware.setSeatHeatRight(profile.seatHeatRight)
            AppLogger.i(TAG, "  SeatHeatRight=${profile.seatHeatRight} → $srOk")

            AppLogger.i(TAG, "Profil '${profile.name}' Katman1 terminé — ok=$ok")
            onComplete?.invoke(ok)

            // ADAS (Katman4) — appliqué dès que mIVehiclePropertyService est prêt
            MG4Hardware.whenKatman4Ready {
                AppLogger.i(TAG, "  Application ADAS pour profil '${profile.name}'")
                when (FirmwareInfo.getGeneration()) {
                    FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.UNKNOWN -> {
                        val oaOk = MG4Hardware.setOverspeedAlarm(profile.overspeedAlarm)
                        AppLogger.i(TAG, "  OverspeedAlarm=${profile.overspeedAlarm} → $oaOk")
                        val stOk = MG4Hardware.setSpeedLimitTone(profile.speedLimitTone)
                        AppLogger.i(TAG, "  SpeedLimitTone=${profile.speedLimitTone} → $stOk")
                        val adOk = MG4Hardware.setMixedIntelligentDrive(profile.adasMode)
                        AppLogger.i(TAG, "  AdasMode=${profile.adasMode} → $adOk")
                    }
                    FirmwareInfo.Gen.SWI68 -> {
                        val swOk = MG4Hardware.setSoundWarning(profile.soundWarning)
                        AppLogger.i(TAG, "  SoundWarning=${profile.soundWarning} → $swOk")
                        val adOk = MG4Hardware.setAccTjaMode(profile.swi68AdasMode)
                        AppLogger.i(TAG, "  Swi68AdasMode=0x${profile.swi68AdasMode.toString(16)} → $adOk")
                    }
                }
            }
        }
    }
}
