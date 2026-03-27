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
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment principal unique du dashboard.
 * Fusionne la logique de DriveRegenFragment, ClimateFragment et AdasFragment
 * dans un seul écran non-scrollable adapté à l'écran tête de l'unité MG4.
 */
class DashboardFragment : Fragment() {

    // ── Drive mode ───────────────────────────────────────────────────────────
    private val driveModeButtons = mutableMapOf<DriveMode, Button>()

    // ── Régénération ─────────────────────────────────────────────────────────
    private val regenButtons = mutableMapOf<RegenLevel, Button>()

    // ── ADAS SWI133 ──────────────────────────────────────────────────────────
    // Mapping mode-index → bouton  (AUTO=2 non visible dans le dashboard)
    private var btnAdasOff: Button?     = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAcc: Button?     = null
    private var btnAdasIca: Button?     = null
    private val swi133AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnAdasOff, 1 to btnAdasLimiteur, 3 to btnAdasAcc, 4 to btnAdasIca)

    // ── ADAS SWI68 ───────────────────────────────────────────────────────────
    private var btnSwi68Off: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null
    private val swi68AdasMap: Map<Int, Button?>
        get() = mapOf(Swi68Mode.OFF to btnSwi68Off, Swi68Mode.ACC to btnSwi68Acc, Swi68Mode.TJA to btnSwi68Tja)

    // ── Climat ───────────────────────────────────────────────────────────────
    private lateinit var switchSteering: Switch
    private lateinit var seatLeftButtons: List<Button>
    private lateinit var seatRightButtons: List<Button>

    // ── Alertes SWI133 ───────────────────────────────────────────────────────
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null

    // ── Alertes SWI68 ────────────────────────────────────────────────────────
    private var switchSoundWarning: Switch? = null

    // ── Couleurs (lazy pour contexte disponible) ──────────────────────────────
    private val colorActive   by lazy { requireContext().getColor(R.color.dash_accent_dim) }
    private val colorInactive by lazy { requireContext().getColor(R.color.dash_btn) }
    private val colorTextActive   by lazy { requireContext().getColor(R.color.dash_accent) }
    private val colorTextInactive by lazy { requireContext().getColor(R.color.text_secondary) }
    private val colorEcoBg   by lazy { requireContext().getColor(R.color.dash_eco_dim) }
    private val colorEcoText by lazy { requireContext().getColor(R.color.dash_eco) }
    private val colorWarnBg  by lazy { requireContext().getColor(R.color.dash_warn_dim) }
    private val colorWarnText by lazy { requireContext().getColor(R.color.dash_warn) }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        applyFirmwareVisibility(view)
        setupListeners()
    }

    // ── Liaison des vues ─────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        // Drive
        driveModeButtons[DriveMode.ECO]    = view.findViewById(R.id.btn_eco)
        driveModeButtons[DriveMode.NORMAL] = view.findViewById(R.id.btn_normal)
        driveModeButtons[DriveMode.SPORT]  = view.findViewById(R.id.btn_sport)
        driveModeButtons[DriveMode.SNOW]   = view.findViewById(R.id.btn_snow)
        driveModeButtons[DriveMode.CUSTOM] = view.findViewById(R.id.btn_custom)

        // Regen
        regenButtons[RegenLevel.OFF]       = view.findViewById(R.id.btn_regen_off)
        regenButtons[RegenLevel.LOW]       = view.findViewById(R.id.btn_regen_low)
        regenButtons[RegenLevel.MEDIUM]    = view.findViewById(R.id.btn_regen_medium)
        regenButtons[RegenLevel.HIGH]      = view.findViewById(R.id.btn_regen_high)
        regenButtons[RegenLevel.ADAPTIVE]  = view.findViewById(R.id.btn_regen_adaptive)
        regenButtons[RegenLevel.ONE_PEDAL] = view.findViewById(R.id.btn_regen_one_pedal)

        // ADAS SWI133
        btnAdasOff      = view.findViewById(R.id.btn_adas_off)
        btnAdasLimiteur = view.findViewById(R.id.btn_adas_limiteur)
        btnAdasAcc      = view.findViewById(R.id.btn_adas_acc)
        btnAdasIca      = view.findViewById(R.id.btn_adas_ica)

        // ADAS SWI68
        btnSwi68Off = view.findViewById(R.id.btn_swi68_off)
        btnSwi68Acc = view.findViewById(R.id.btn_swi68_acc)
        btnSwi68Tja = view.findViewById(R.id.btn_swi68_tja)

        // Climat
        switchSteering   = view.findViewById(R.id.switch_steering_heat)
        seatLeftButtons  = listOf(
            R.id.btn_seat_left_0, R.id.btn_seat_left_1,
            R.id.btn_seat_left_2, R.id.btn_seat_left_3
        ).map { view.findViewById(it) }
        seatRightButtons = listOf(
            R.id.btn_seat_right_0, R.id.btn_seat_right_1,
            R.id.btn_seat_right_2, R.id.btn_seat_right_3
        ).map { view.findViewById(it) }

        // Alertes
        switchOverspeed    = view.findViewById(R.id.switch_overspeed)
        switchSpeedTone    = view.findViewById(R.id.switch_speed_tone)
        switchSoundWarning = view.findViewById(R.id.switch_sound_warning)
    }

    /** Affiche la section ADAS et alertes correspondant au firmware détecté. */
    private fun applyFirmwareVisibility(view: View) {
        val isSWI68 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68
        view.findViewById<View>(R.id.adas_group_swi133).visibility    = if (!isSWI68) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.adas_group_swi68).visibility     = if (isSWI68)  View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi133).visibility  = if (!isSWI68) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi68).visibility   = if (isSWI68)  View.VISIBLE else View.GONE
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private fun setupListeners() {
        val isSWI68 = FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI68

        // Drive mode
        driveModeButtons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                applyDriveModeUI(mode)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setDriveMode(mode) }
            }
        }

        // Regen
        regenButtons.forEach { (level, btn) ->
            btn.setOnClickListener {
                applyRegenUI(level)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setRegenLevel(level) }
            }
        }

        // ADAS
        if (!isSWI68) {
            swi133AdasMap.forEach { (modeIndex, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setMixedIntelligentDrive(modeIndex)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi133AdasUI(modeIndex) }
                    }
                }
            }
        } else {
            swi68AdasMap.forEach { (modeValue, btn) ->
                btn?.setOnClickListener {
                    CoroutineScope(Dispatchers.IO).launch {
                        MG4Hardware.setAccTjaMode(modeValue)
                        withContext(Dispatchers.Main) { if (isAdded) applySwi68AdasUI(modeValue) }
                    }
                }
            }
        }

        // Volant chauffant (guard isPressed pour éviter le déclenchement programmatique)
        switchSteering.setOnCheckedChangeListener { _, checked ->
            if (switchSteering.isPressed)
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSteeringHeat(checked) }
        }

        // Sièges
        setupSeatButtons(seatLeftButtons)  { level ->
            CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatLeft(level) }
        }
        setupSeatButtons(seatRightButtons) { level ->
            CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatRight(level) }
        }

        // Alertes SWI133
        if (!isSWI68) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (switchOverspeed?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (switchSpeedTone?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
        }

        // Alerte SWI68
        if (isSWI68) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (switchSoundWarning?.isPressed == true)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSoundWarning(checked) }
            }
        }
    }

    // ── Rafraîchissement de l'état hardware ──────────────────────────────────

    override fun onResume() {
        super.onResume()
        refreshDriveRegen()
        refreshClimate()
        MG4Hardware.whenKatman4Ready { if (isAdded) refreshAdas() }
    }

    private fun refreshDriveRegen() {
        CoroutineScope(Dispatchers.IO).launch {
            val mode  = MG4Hardware.getDriveMode()
            val regen = MG4Hardware.getRegenLevel()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                mode?.let  { applyDriveModeUI(it) }
                regen?.let { applyRegenUI(it) }
                if (mode == null && regen == null)
                    view?.postDelayed({ if (isAdded) refreshDriveRegen() }, 3_000)
            }
        }
    }

    private fun refreshClimate() {
        CoroutineScope(Dispatchers.IO).launch {
            val steeringOn = MG4Hardware.isSteeringHeatOn()
            val leftLevel  = MG4Hardware.getSeatHeatLeft()
            val rightLevel = MG4Hardware.getSeatHeatRight()
            val ready = MG4Hardware.getIntPropertyHvac(MG4Hardware.PROP_SEAT_HEAT_L, 0x75) >= 0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ready) {
                    switchSteering.isChecked = steeringOn
                    applySeatUI(seatLeftButtons, leftLevel)
                    applySeatUI(seatRightButtons, rightLevel)
                } else {
                    view?.postDelayed({ if (isAdded) refreshClimate() }, 3_000)
                }
            }
        }
    }

    private fun refreshAdas() {
        CoroutineScope(Dispatchers.IO).launch {
            when (FirmwareInfo.getGeneration()) {
                FirmwareInfo.Gen.SWI133, FirmwareInfo.Gen.UNKNOWN -> refreshSwi133Adas()
                FirmwareInfo.Gen.SWI68                            -> refreshSwi68Adas()
            }
        }
    }

    private suspend fun refreshSwi133Adas() {
        val adasMode  = MG4Hardware.getMixedIntelligentDrive()
        val overspeed = MG4Hardware.isOverspeedAlarmOn()
        val speedTone = MG4Hardware.isSpeedLimitToneOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            applySwi133AdasUI(adasMode)
        }
    }

    private suspend fun refreshSwi68Adas() {
        val mode  = MG4Hardware.getAccTjaMode()
        val sound = MG4Hardware.isSoundWarningOn()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            switchSoundWarning?.isChecked = sound
            applySwi68AdasUI(mode)
        }
    }

    // ── Helpers UI ───────────────────────────────────────────────────────────

    private fun applyDriveModeUI(mode: DriveMode) {
        driveModeButtons.forEach { (m, btn) ->
            val (bg, text) = when {
                m != mode        -> colorInactive to colorTextInactive
                m == DriveMode.ECO   -> colorEcoBg   to colorEcoText
                m == DriveMode.SPORT -> colorWarnBg  to colorWarnText
                else                 -> colorActive   to colorTextActive
            }
            btn.backgroundTintList = ColorStateList.valueOf(bg)
            btn.setTextColor(text)
        }
        setRegenEnabled(mode != DriveMode.SNOW)
    }

    private fun applyRegenUI(level: RegenLevel) {
        regenButtons.forEach { (l, btn) ->
            val active = l == level
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun setRegenEnabled(enabled: Boolean) {
        regenButtons.values.forEach { btn ->
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun applySwi133AdasUI(activeMode: Int) {
        swi133AdasMap.forEach { (modeIndex, btn) ->
            val active = modeIndex == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applySwi68AdasUI(activeMode: Int) {
        swi68AdasMap.forEach { (modeValue, btn) ->
            val active = modeValue == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun setupSeatButtons(buttons: List<Button>, onLevel: (Int) -> Unit) {
        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                applySeatUI(buttons, index)
                onLevel(index)
            }
        }
    }

    private fun applySeatUI(buttons: List<Button>, activeIndex: Int) {
        buttons.forEachIndexed { i, btn ->
            val active = i == activeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }
}
