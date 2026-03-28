package com.mg4.control.model

import java.util.UUID

data class DrivingProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val driveMode: DriveMode,
    val regenLevel: RegenLevel,
    val steeringHeat: Boolean = false,
    val seatHeatLeft: Int = 0,        // 0=off, 1, 2, 3
    val seatHeatRight: Int = 0,
    // ADAS SWI133 (Katman4) — valeurs par défaut OFF pour compatibilité profils existants
    val overspeedAlarm: Boolean = false,
    val speedLimitTone: Boolean = false,
    val adasMode: Int = 0,            // 0=Off, 1=Limiteur, 2=Auto, 3=ACC, 4=ICA
    // ADAS SWI68 — champs distincts pour isoler les configurations par firmware
    val soundWarning: Boolean = false,
    val swi68AdasMode: Int = 0x4,     // 0x4=Off, 0x1=ACC, 0x2=TJA
    // AEB — Système anti-collision avant (commun SWI133 + SWI68)
    val aebEnabled: Boolean = false,   // false=OFF, true=ON
    val aebMode: Int = 1,              // 1=Alerte seule, 2=Alerte+Freinage auto
    val isDefault: Boolean = false
)
