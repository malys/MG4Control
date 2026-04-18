package com.mg4.control.update

import android.os.Environment
import android.util.Log

/**
 * Nettoie automatiquement les anciens fichiers MGControl*.apk dans le dossier Téléchargements.
 * Ne conserve que les [MAX_APK] plus récents.
 */
object ApkCleanup {

    private const val TAG     = "ApkCleanup"
    private const val MAX_APK = 5

    fun cleanIfNeeded() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apkFiles = downloadsDir.listFiles { f ->
            f.isFile && f.name.startsWith("MGControl") && f.name.endsWith(".apk")
        } ?: return

        if (apkFiles.size <= MAX_APK) return

        val toDelete = apkFiles.sortedBy { it.lastModified() }.take(apkFiles.size - MAX_APK)
        toDelete.forEach { file ->
            if (file.delete()) {
                Log.i(TAG, "Supprimé : ${file.name}")
            } else {
                Log.w(TAG, "Échec suppression : ${file.name}")
            }
        }
        Log.i(TAG, "${toDelete.size} APK(s) supprimé(s) — ${MAX_APK} conservé(s)")
    }
}
