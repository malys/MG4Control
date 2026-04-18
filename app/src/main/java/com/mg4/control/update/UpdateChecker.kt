package com.mg4.control.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérifie sur l'API GitHub Releases si une version plus récente est disponible.
 * L'appel est asynchrone ; le callback [onUpdateAvailable] est exécuté sur le main thread.
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/SliDeeN/MG4Control/releases/latest"

    private const val PREFS_SKIP      = "mg4_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    fun check(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Version actuellement installée
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
                    ?: return@launch

                // Version ignorée par l'utilisateur
                val skippedVersion = context
                    .getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
                    .getString(KEY_SKIP_VERSION, null)

                // Requête GitHub API
                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "MG4Control-Android")
                    connectTimeout = 10_000
                    readTimeout    = 10_000
                }

                if (conn.responseCode != 200) return@launch

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val tagName      = json.getString("tag_name")           // "v2.1"
                val versionName  = tagName.trimStart('v')                // "2.1"
                val releaseNotes = json.optString("body", "").take(400)

                // Cherche l'asset .apk dans la release
                val assets = json.optJSONArray("assets") ?: return@launch
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isEmpty()) return@launch

                // Compare uniquement si version distante > version locale
                if (isNewer(versionName, currentVersion)) {
                    // Si cette version a été explicitement ignorée → ne pas redemander
                    if (versionName == skippedVersion) {
                        withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                        return@launch
                    }
                    val skippedCount = versionHops(currentVersion, versionName)
                    val info = UpdateInfo(versionName, tagName, apkUrl, releaseNotes, skippedCount)
                    withContext(Dispatchers.Main) { onUpdateAvailable(info) }
                } else {
                    // Check réussi, application déjà à jour
                    withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                }

            } catch (_: Exception) {
                // Pas de réseau, timeout, JSON malformé → callback onError
                withContext(Dispatchers.Main) { onError?.invoke() }
            }
        }
    }

    /**
     * Sauvegarde la version [version] comme "à ne plus rappeler".
     * Appelé quand l'utilisateur clique sur "Ne plus me rappeler".
     */
    fun skipVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIP_VERSION, version)
            .apply()
    }

    /**
     * Retourne true si [remote] est une version supérieure à [current].
     * Comparaison sémantique segment par segment (ex: "2.1.3" > "2.1.2").
     */
    private fun isNewer(remote: String, current: String): Boolean {
        fun segments(v: String) =
            v.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }

        val r = segments(remote)
        val c = segments(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false // versions identiques
    }

    /**
     * Estime le nombre de versions intermédiaires entre [from] et [to].
     * Somme des incréments par segment (ex: "2.4.0" → "2.5.1" = 0+1+1 = 2).
     */
    private fun versionHops(from: String, to: String): Int {
        fun segments(v: String) =
            v.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }

        val f = segments(from)
        val t = segments(to)
        return (0 until maxOf(f.size, t.size)).sumOf { i ->
            maxOf(0, t.getOrElse(i) { 0 } - f.getOrElse(i) { 0 })
        }
    }
}
