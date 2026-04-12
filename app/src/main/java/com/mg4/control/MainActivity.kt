package com.mg4.control

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.button.MaterialButton
import com.mg4.control.service.MG4ControlService
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateDialogManager
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.LocaleHelper

class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavController

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init firmware EN PREMIER — avant toute inflation de fragment
        // Charge le mode forcé éventuel depuis les prefs
        FirmwareInfo.initWithContext(this)

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
        checkUnknownFirmware()  // après setupFirmwareChips pour que les chips soient prêtes
        navigateToDefaultScreen(savedInstanceState)
        checkForUpdates()
    }

    // ── Navigation vers l'écran par défaut au démarrage ─────────────────────

    private fun navigateToDefaultScreen(savedInstanceState: android.os.Bundle?) {
        // Ne naviguer que si c'est un vrai démarrage (pas une rotation / recreate)
        if (savedInstanceState != null) return
        val prefs = getSharedPreferences("mg4_settings", android.content.Context.MODE_PRIVATE)
        when (prefs.getString("default_screen", "dashboard")) {
            "profiles"  -> navController.navigate(R.id.profileFragment)
            "shortcuts" -> navController.navigate(R.id.shortcutsFragment)
            // "dashboard" → rien à faire, c'est déjà le startDestination
        }
    }

    // ── Vérification de mise à jour au démarrage ──────────────────────────────

    private fun checkForUpdates() {
        UpdateChecker.check(
            context = this,
            onUpdateAvailable = { updateInfo ->
                if (!isFinishing && !isDestroyed) {
                    UpdateDialogManager.show(this, updateInfo)
                }
            }
            // onNoUpdate et onError ignorés au démarrage — silencieux si tout va bien
        )
    }

    // ── Logo "MG4Control" avec "Control" en couleur accent ───────────────────

    private fun setupLogo() {
        val tv = findViewById<TextView>(R.id.topbar_logo)
        val full = getString(R.string.app_name)           // "MG4 Control"
        val accent = getColor(R.color.dash_accent)
        val span = SpannableString(full)
        val splitAt = full.indexOf(' ').takeIf { it >= 0 }?.plus(1) ?: 0
        if (splitAt > 0) span.setSpan(ForegroundColorSpan(accent), splitAt, full.length, 0)
        tv.text = span
    }

    // ── Indicateur firmware (chips SWI133 / SWI68 / SWI69 / SWI131 / SWI165) ──

    private fun setupFirmwareChips() {
        val chip133 = findViewById<TextView>(R.id.chip_swi133)
        val chip68  = findViewById<TextView>(R.id.chip_swi68)
        val chip69  = findViewById<TextView>(R.id.chip_swi69)
        val chip131 = findViewById<TextView>(R.id.chip_swi131)
        val chip165 = findViewById<TextView>(R.id.chip_swi165)
        val gen     = FirmwareInfo.getGeneration()
        val forced  = FirmwareInfo.isForced(this)

        fun styleChipActive(tv: TextView) {
            tv.setBackgroundResource(R.drawable.bg_chip_active)
            tv.setTextColor(getColor(R.color.dash_accent))
            tv.alpha = 1f
            tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        fun styleChipInactive(tv: TextView) {
            tv.setBackgroundResource(R.drawable.bg_chip_inactive)
            tv.setTextColor(getColor(R.color.dash_text_lo))
            tv.alpha = 0.4f
            tv.paintFlags = tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }

        fun styleChipSelectable(tv: TextView) {
            // Firmware inconnu sans choix forcé : chip cliquable, surlignée en rouge
            tv.setBackgroundResource(R.drawable.bg_chip_inactive)
            tv.setTextColor(getColor(R.color.dash_danger))
            tv.alpha = 0.75f
            tv.paintFlags = tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        val isNaturalUnknown = gen == FirmwareInfo.Gen.UNKNOWN && !forced
        val allChips = listOf(chip133, chip68, chip69, chip131, chip165)

        when {
            isNaturalUnknown -> {
                // Les cinq chips en mode "à choisir" (rouge dim, aucune barrée)
                allChips.forEach { styleChipSelectable(it) }
            }
            gen == FirmwareInfo.Gen.SWI165 -> {
                styleChipActive(chip165)
                listOf(chip133, chip68, chip69, chip131).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI131 -> {
                styleChipActive(chip131)
                listOf(chip133, chip68, chip69, chip165).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI69 -> {
                styleChipActive(chip69)
                listOf(chip133, chip68, chip131, chip165).forEach { styleChipInactive(it) }
            }
            gen == FirmwareInfo.Gen.SWI68 -> {
                styleChipActive(chip68)
                listOf(chip133, chip69, chip131, chip165).forEach { styleChipInactive(it) }
            }
            else -> { // SWI133 ou forcé SWI133
                styleChipActive(chip133)
                listOf(chip68, chip69, chip131, chip165).forEach { styleChipInactive(it) }
            }
        }

        // Chips cliquables si firmware inconnu (naturel ou forcé) pour changer de mode
        if (gen == FirmwareInfo.Gen.UNKNOWN || forced) {
            chip133.setOnClickListener {
                FirmwareInfo.forceGeneration(this, FirmwareInfo.Gen.SWI133)
                recreate()
            }
            chip68.setOnClickListener {
                FirmwareInfo.forceGeneration(this, FirmwareInfo.Gen.SWI68)
                recreate()
            }
            chip69.setOnClickListener {
                FirmwareInfo.forceGeneration(this, FirmwareInfo.Gen.SWI69)
                recreate()
            }
            chip131.setOnClickListener {
                FirmwareInfo.forceGeneration(this, FirmwareInfo.Gen.SWI131)
                recreate()
            }
            chip165.setOnClickListener {
                FirmwareInfo.forceGeneration(this, FirmwareInfo.Gen.SWI165)
                recreate()
            }
        }
    }

    // ── Dialog firmware non reconnu ───────────────────────────────────────────

    private fun checkUnknownFirmware() {
        // Ne montre le dialog que si le firmware est inconnu ET pas encore de choix forcé
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) return
        if (FirmwareInfo.isForced(this)) return

        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_unknown_firmware, null)

        // Affiche la chaîne firmware brute dans le badge (ex: "SWI69-12345")
        dialogView.findViewById<TextView>(R.id.tv_fw_detected_badge).text =
            FirmwareInfo.getDetectedString()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_close_app).setOnClickListener {
            finishAffinity()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_fw_continue).setOnClickListener {
            dialog.dismiss()
            // L'utilisateur peut maintenant taper sur les chips SWI133/SWI68
        }

        dialog.show()
    }

    // ── Boutons de navigation dans la top-bar ─────────────────────────────────

    private fun setupNavButtons() {
        val btnShortcuts = findViewById<MaterialButton>(R.id.btn_nav_shortcuts)
        val btnProfiles  = findViewById<MaterialButton>(R.id.btn_nav_profiles)
        val btnSettings  = findViewById<MaterialButton>(R.id.btn_nav_settings)

        btnShortcuts.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.shortcutsFragment -> navController.popBackStack()
                else                   -> navController.navigate(R.id.shortcutsFragment)
            }
        }

        btnProfiles.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.profileFragment -> navController.popBackStack()
                else                 -> navController.navigate(R.id.profileFragment)
            }
        }

        btnSettings.setOnClickListener {
            when (navController.currentDestination?.id) {
                R.id.settingsFragment -> navController.popBackStack()
                else                  -> navController.navigate(R.id.settingsFragment)
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val accent   = getColor(R.color.dash_accent_dim)
            val inactive = getColor(R.color.dash_btn)
            btnShortcuts.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (destination.id == R.id.shortcutsFragment) accent else inactive
            )
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
