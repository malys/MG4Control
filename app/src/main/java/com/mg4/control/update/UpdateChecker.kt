package com.mg4.control.update

import android.content.Context
import com.mg4.control.debug.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vérifie si une version plus récente est disponible.
 * Stratégie : GitHub en priorité, GitLab en fallback si GitHub est inaccessible
 * (timeout, erreur réseau, rate-limit, repo privé, etc.).
 */
object UpdateChecker {

    private const val TAG = "MG4_UPDATE"

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/SliDeeN/MG4Control/releases/latest"

    private const val GITLAB_API_URL =
        "https://gitlab.com/api/v4/projects/SliDeeN%2Fmg4control/releases/permalink/latest"

    private const val PREFS_SKIP       = "mg4_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    // -------------------------------------------------------------------------
    // Données brutes extraites depuis GitHub ou GitLab
    // -------------------------------------------------------------------------

    private data class RawRelease(
        val tagName: String,
        val apkUrl: String,
        val releaseNotes: String,
        val source: String          // "GitHub" ou "GitLab" — pour les logs
    )

    // -------------------------------------------------------------------------
    // Point d'entrée public
    // -------------------------------------------------------------------------

    fun check(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
                    ?: return@launch

                val skippedVersion = context
                    .getSharedPreferences(PREFS_SKIP, Context.MODE_PRIVATE)
                    .getString(KEY_SKIP_VERSION, null)

                // Essai GitHub → fallback GitLab
                val release = fetchFromGitHub()
                    ?: fetchFromGitLab()
                    ?: run {
                        AppLogger.w(TAG, "GitHub et GitLab inaccessibles")
                        withContext(Dispatchers.Main) { onError?.invoke() }
                        return@launch
                    }

                AppLogger.i(TAG, "Release récupérée depuis ${release.source} : ${release.tagName}")

                val versionName = release.tagName.trimStart('v')

                if (isNewer(versionName, currentVersion)) {
                    if (versionName == skippedVersion) {
                        withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                        return@launch
                    }
                    val skippedCount = versionHops(currentVersion, versionName)
                    val info = UpdateInfo(
                        versionName, release.tagName, release.apkUrl,
                        release.releaseNotes, skippedCount
                    )
                    withContext(Dispatchers.Main) { onUpdateAvailable(info) }
                } else {
                    withContext(Dispatchers.Main) { onNoUpdate?.invoke() }
                }

            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke() }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Requête GitHub
    // -------------------------------------------------------------------------

    private fun fetchFromGitHub(): RawRelease? {
        return try {
            val conn = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }
            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "GitHub réponse ${conn.responseCode} → fallback GitLab")
                conn.disconnect()
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val tagName = json.getString("tag_name")
            val notes   = json.optString("body", "").take(400)
            val assets  = json.optJSONArray("assets") ?: return null
            var apkUrl  = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isEmpty()) {
                AppLogger.w(TAG, "GitHub : aucun asset .apk trouvé → fallback GitLab")
                return null
            }
            RawRelease(tagName, apkUrl, notes, "GitHub")

        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub inaccessible : ${e.message} → fallback GitLab")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Requête GitLab (fallback)
    // -------------------------------------------------------------------------

    private fun fetchFromGitLab(): RawRelease? {
        return try {
            val conn = (URL(GITLAB_API_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "MG4Control-Android")
                connectTimeout = 10_000
                readTimeout    = 10_000
            }
            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "GitLab réponse ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            val tagName = json.getString("tag_name")
            val notes   = json.optString("description", "").take(400)

            // Structure GitLab : assets.links[] (contrairement à assets[] sur GitHub)
            val links  = json.optJSONObject("assets")?.optJSONArray("links") ?: return null
            var apkUrl = ""
            for (i in 0 until links.length()) {
                val link = links.getJSONObject(i)
                if (link.getString("name").endsWith(".apk")) {
                    apkUrl = link.optString("direct_asset_url").ifEmpty {
                        link.optString("url")
                    }
                    break
                }
            }
            if (apkUrl.isEmpty()) {
                AppLogger.w(TAG, "GitLab : aucun asset .apk trouvé dans les links")
                return null
            }
            RawRelease(tagName, apkUrl, notes, "GitLab")

        } catch (e: Exception) {
            AppLogger.w(TAG, "GitLab inaccessible : ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sauvegarde la version [version] comme "à ne plus rappeler".
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
        return false
    }

    /**
     * Estime le nombre de versions intermédiaires entre [from] et [to].
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
