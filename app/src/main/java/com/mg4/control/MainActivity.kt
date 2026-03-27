package com.mg4.control

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.service.MG4ControlService
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.LocaleHelper

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Premier lancement : choix de la langue avant tout
        if (LocaleHelper.isFirstLaunch(this)) {
            showLanguagePicker()
            return
        }

        setContentView(R.layout.activity_main)

        startForegroundService(Intent(this, MG4ControlService::class.java))

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        setupLogo()
        setupFirmwareChips()
        setupNavButtons()
    }

    // ── Logo "MG4Control" avec "Control" en couleur accent ───────────────────

    private fun setupLogo() {
        val tv = findViewById<TextView>(R.id.topbar_logo)
        val full = getString(R.string.app_name)           // "MG4 Control"
        val accent = getColor(R.color.dash_accent)
        val span = SpannableString(full)
        // Colore tout ce qui suit "MG4" en accent
        val splitAt = full.indexOf(' ').takeIf { it >= 0 }?.plus(1) ?: 0
        if (splitAt > 0) span.setSpan(ForegroundColorSpan(accent), splitAt, full.length, 0)
        tv.text = span
    }

    // ── Indicateur firmware (chips SWI133 / SWI68) ───────────────────────────

    private fun setupFirmwareChips() {
        val chip133 = findViewById<TextView>(R.id.chip_swi133)
        val chip68  = findViewById<TextView>(R.id.chip_swi68)
        val gen = FirmwareInfo.getGeneration()

        fun styleChip(tv: TextView, active: Boolean) {
            if (active) {
                tv.setBackgroundResource(R.drawable.bg_chip_active)
                tv.setTextColor(getColor(R.color.dash_accent))
                tv.alpha = 1f
                tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                tv.setBackgroundResource(R.drawable.bg_chip_inactive)
                tv.setTextColor(getColor(R.color.dash_text_lo))
                tv.alpha = 0.4f
                tv.paintFlags = tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }

        val isSWI68 = gen == FirmwareInfo.Gen.SWI68
        styleChip(chip133, !isSWI68)
        styleChip(chip68,   isSWI68)
    }

    // ── Boutons de navigation dans la top-bar ─────────────────────────────────

    private fun setupNavButtons() {
        val btnProfiles = findViewById<MaterialButton>(R.id.btn_nav_profiles)
        val btnSettings = findViewById<MaterialButton>(R.id.btn_nav_settings)

        // PROFILS : navigue / revient au dashboard si déjà sur l'écran profils
        btnProfiles.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.profileFragment -> navController.popBackStack()
                else                 -> navController.navigate(R.id.profileFragment)
            }
        }

        // RÉGLAGES : ouvre les réglages, ou ferme si déjà ouvert
        btnSettings.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.settingsFragment -> navController.popBackStack()
                else                  -> navController.navigate(R.id.settingsFragment)
            }
        }

        // Surligne le bouton actif selon la destination courante
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val accent   = getColor(R.color.dash_accent_dim)
            val inactive = getColor(R.color.dash_btn)
            btnProfiles.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.profileFragment) accent else inactive
            )
            btnSettings.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.settingsFragment) accent else inactive
            )
        }
    }

    // ── Dialogue de choix de langue au premier lancement ─────────────────────

    private fun showLanguagePicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language_pick_title)
            .setMessage(R.string.settings_language_pick_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.settings_language_fr) { _, _ ->
                LocaleHelper.setLanguage(this, "fr")
                recreate()
            }
            .setNegativeButton(R.string.settings_language_en) { _, _ ->
                LocaleHelper.setLanguage(this, "en")
                recreate()
            }
            .show()
    }
}
