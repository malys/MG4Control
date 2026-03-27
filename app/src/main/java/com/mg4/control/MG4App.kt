package com.mg4.control

import android.app.Application
import android.content.Context
import com.mg4.control.util.LocaleHelper

class MG4App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Thème fixe nuit (hardunit sombre)
        androidx.appcompat.app.AppCompatDelegate
            .setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    }
}
