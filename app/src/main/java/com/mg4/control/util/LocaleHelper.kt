package com.mg4.control.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    private const val PREFS      = "mg4_settings"
    private const val KEY_LANG   = "language"
    private const val KEY_LANG_SET = "language_set"

    /** Retourne "fr" ou "en" (défaut : "fr"). */
    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "fr") ?: "fr"

    /** Sauvegarde le choix et marque le premier lancement comme fait. */
    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, lang)
            .putBoolean(KEY_LANG_SET, true)
            .apply()
    }

    /** True si l'utilisateur n'a pas encore choisi de langue. */
    fun isFirstLaunch(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANG_SET, false)

    /** Applique la locale sauvegardée au contexte fourni et retourne le contexte modifié. */
    fun applyLocale(context: Context): Context {
        val lang   = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
