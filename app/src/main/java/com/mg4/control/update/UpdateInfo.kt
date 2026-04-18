package com.mg4.control.update

/**
 * Informations sur une release GitHub disponible.
 */
data class UpdateInfo(
    /** "2.1"   — version parsée depuis le tag GitHub */
    val versionName: String,
    /** "v2.1"  — tag brut de la release */
    val tagName: String,
    /** URL de téléchargement directe du fichier .apk */
    val apkUrl: String,
    /** Corps de la release note (tronqué à 400 caractères) */
    val releaseNotes: String,
    /** Nombre de versions ignorées depuis la version actuellement installée */
    val skippedCount: Int = 0
)
