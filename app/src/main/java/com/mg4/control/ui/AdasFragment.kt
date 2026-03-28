package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.*

class AdasFragment : Fragment() {

    // SWI133 views
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var btnAdasOff: Button? = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAuto: Button? = null
    private var btnAdasAcc: Button? = null
    private var btnAdasIca: Button? = null

    // SWI68 views
    private var switchSoundWarning: Switch? = null
    private var btnSwi68Off: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null

    // AEB views (communes SWI133 + SWI68)
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null

    private val swi133Buttons get() = listOfNotNull(
        btnAdasOff, btnAdasLimiteur, btnAdasAuto, btnAdasAcc, btnAdasIca
    )

    // SWI68 mode value → button
    private val swi68Buttons: Map<Int, Button?> get() = mapOf(
        Swi68Mode.OFF to btnSwi68Off,
        Swi68Mode.ACC to btnSwi68Acc,
        Swi68Mode.TJA to btnSwi68Tja
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_adas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // ── Références views ────────────────────────────────────────────────
        switchOverspeed   = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone   = view.findViewById(R.id.switch_speed_tone)
        btnAdasOff        = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur   = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAuto       = view.findViewById(R.id.btn_adas_auto)
        btnAdasAcc        = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca        = view.findViewById(R.id.btn_adas_ica)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
        btnSwi68Off       = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Acc       = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja       = view.findViewById(R.id.btn_swi68_tja)
        switchAeb         = view.findViewById(R.id.switch_aeb)
        btnAebAlarm       = view.findViewById(R.id.btn_aeb_alarm)
        btnAebAlarmBrake  = view.findViewById(R.id.btn_aeb_alarm_brake)

        // ── Afficher la bonne section selon le firmware ──────────────────────
        val gen = FirmwareInfo.getGeneration()
        val isKnown = gen != FirmwareInfo.Gen.UNKNOWN
        view.findViewById<View>(R.id.section_swi133).visibility =
            if (gen == FirmwareInfo.Gen.SWI133 || gen == FirmwareInfo.Gen.UNKNOWN) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.section_swi68).visibility =
            if (gen == FirmwareInfo.Gen.SWI68) View.VISIBLE else View.GONE
        // Ligne du bas (AEB + alertes) — disponible si firmware connu
        view.findViewById<View>(R.id.section_bottom_row).visibility =
            if (isKnown) View.VISIBLE else View.GONE
        // Alertes : colonne droite — selon firmware
        view.findViewById<View>(R.id.alerts_swi133).visibility =
            if (gen == FirmwareInfo.Gen.SWI133) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_swi68).visibility =
            if (gen == FirmwareInfo.Gen.SWI68) View.VISIBLE else View.GONE

        // ── Listeners SWI133 ─────────────────────────────────────────────────
        if (gen != FirmwareInfo.Gen.SWI68) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
            swi133Buttons.forEachIndexed { index, btn ->
                btn.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setMixedIntelligentDrive(index)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133ModeUI(index) }
                    }
                }
            }
        }

        // ── Listeners AEB (communs SWI133 + SWI68) ──────────────────────────
        if (isKnown) {
            switchAeb?.setOnCheckedChangeListener { _, checked ->
                if (switchAeb?.isPressed == true) {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setAebEnabled(checked)
                        withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                    }
                }
            }
            btnAebAlarm?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebMode(AebMode.ALARM)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM) }
                }
            }
            btnAebAlarmBrake?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebMode(AebMode.ALARM_BRAKE)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeUI(AebMode.ALARM_BRAKE) }
                }
            }
        }

        // ── Listeners SWI68 ──────────────────────────────────────────────────
        if (gen == FirmwareInfo.Gen.SWI68) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (switchSoundWarning?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSoundWarning(checked) }
            }
            val swi68BtnList = listOf(
                Swi68Mode.OFF to btnSwi68Off,
                Swi68Mode.ACC to btnSwi68Acc,
                Swi68Mode.TJA to btnSwi68Tja
            )
            swi68BtnList.forEach { (modeValue, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setAccTjaMode(modeValue)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68ModeUI(modeValue) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) refreshState()
        }
    }

    private fun refreshState() {
        val gen = FirmwareInfo.getGeneration()
        CoroutineScope(Dispatchers.IO).launch {
            when (gen) {
                FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.UNKNOWN -> refreshSwi133()
                FirmwareInfo.Gen.SWI68 -> refreshSwi68()
            }
        }
    }

    private suspend fun refreshSwi133() {
        val adasMode  = MG4Hardware.getMixedIntelligentDrive()
        val overspeed = MG4Hardware.isOverspeedAlarmOn()
        val speedTone = MG4Hardware.isSpeedLimitToneOn()
        val aebOn     = MG4Hardware.isAebEnabled()
        val aebMode   = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            applySwi133ModeUI(adasMode)
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68() {
        val mode    = MG4Hardware.getAccTjaMode()
        val sound   = MG4Hardware.isSoundWarningOn()
        val aebOn   = MG4Hardware.isAebEnabled()
        val aebMode = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshState() }, 2_000)
                return@withContext
            }
            switchSoundWarning?.isChecked = sound
            applySwi68ModeUI(mode)
            switchAeb?.isChecked = aebOn
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun applySwi133ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi133Buttons.forEachIndexed { i, btn ->
            btn.backgroundTintList = ColorStateList.valueOf(if (i == activeMode) accent else def)
        }
    }

    private fun applySwi68ModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        swi68Buttons.forEach { (modeValue, btn) ->
            btn?.backgroundTintList = ColorStateList.valueOf(if (modeValue == activeMode) accent else def)
        }
    }

    private fun applyAebModeUI(activeMode: Int) {
        val accent = requireContext().getColor(R.color.accent_eco)
        val def    = requireContext().getColor(R.color.bg_button)
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM) accent else def)
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) accent else def)
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }
}
