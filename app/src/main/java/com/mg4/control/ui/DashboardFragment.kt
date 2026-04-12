package com.mg4.control.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.AebSensitivity
import com.mg4.control.hardware.MG4Hardware.ElkMode
import com.mg4.control.hardware.MG4Hardware.ElkSensitivity
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.RegenLevel
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment principal du dashboard — ViewPager2 horizontal.
 *   Page 0 : paramètres de conduite, climat, alertes, AEB
 *   Page 1 : Assistant de sortie de voie (ELK)
 */
class DashboardFragment : Fragment() {

    // ── ViewPager ────────────────────────────────────────────────────────────
    private var pager: ViewPager2? = null
    private var dots: Array<View>? = null

    // ── Page 0 — Drive mode ─────────────────────────────────────────────────
    private val driveModeButtons = mutableMapOf<DriveMode, Button>()

    // ── Page 0 — Régénération ───────────────────────────────────────────────
    private val regenButtons = mutableMapOf<RegenLevel, Button>()

    // ── Page 0 — ADAS SWI133 ────────────────────────────────────────────────
    private var btnAdasOff: Button?     = null
    private var btnAdasLimiteur: Button? = null
    private var btnAdasAcc: Button?     = null
    private var btnAdasIca: Button?     = null
    private val swi133AdasMap: Map<Int, Button?>
        get() = mapOf(0 to btnAdasOff, 1 to btnAdasLimiteur, 3 to btnAdasAcc, 4 to btnAdasIca)

    // ── Page 0 — ADAS SWI68 ─────────────────────────────────────────────────
    private var btnSwi68Off: Button? = null
    private var btnSwi68Acc: Button? = null
    private var btnSwi68Tja: Button? = null
    private val swi68AdasMap: Map<Int, Button?>
        get() = mapOf(Swi68Mode.OFF to btnSwi68Off, Swi68Mode.ACC to btnSwi68Acc, Swi68Mode.TJA to btnSwi68Tja)

    // ── Page 0 — Climat ─────────────────────────────────────────────────────
    private var switchSteering: Switch? = null
    private var seatLeftButtons: List<Button>? = null
    private var seatRightButtons: List<Button>? = null

    // ── Page 0 — Alertes ────────────────────────────────────────────────────
    private var switchOverspeed: Switch? = null
    private var switchSpeedTone: Switch? = null
    private var switchSoundWarning: Switch? = null

    // ── AEB : page 0 pour VSM-based, page 1 (SWI133) pour les autres ───────────
    private var switchAeb: Switch? = null
    private var btnAebAlarm: Button? = null
    private var btnAebAlarmBrake: Button? = null
    private var btnAebSenLow: Button? = null
    private var btnAebSenStandard: Button? = null
    private var btnAebSenHigh: Button? = null
    private val aebSenMap: Map<Int, Button?>
        get() = mapOf(AebSensitivity.LOW to btnAebSenLow, AebSensitivity.STANDARD to btnAebSenStandard, AebSensitivity.HIGH to btnAebSenHigh)

    // ── Page 1 — ELK ────────────────────────────────────────────────────────
    private var switchElk: Switch? = null
    private var btnElkAlert: Button? = null
    private var btnElkAssist: Button? = null
    private var btnElkEmergency: Button? = null
    private var btnElkSenLow: Button? = null
    private var btnElkSenStandard: Button? = null
    private var btnElkSenHigh: Button? = null
    private val elkModeMap: Map<Int, Button?>
        get() = mapOf(ElkMode.ALERT to btnElkAlert, ElkMode.ASSIST to btnElkAssist, ElkMode.EMERGENCY to btnElkEmergency)
    private val elkSenMap: Map<Int, Button?>
        get() = mapOf(ElkSensitivity.LOW to btnElkSenLow, ElkSensitivity.STANDARD to btnElkSenStandard, ElkSensitivity.HIGH to btnElkSenHigh)

    /** True pendant les mises à jour programmatiques des Switch — bloque les listeners. */
    private var isRefreshing = false
    /** Dernier mode ELK actif connu (pour restaurer le mode lors du toggle ON). */
    private var lastActiveElkMode = ElkMode.EMERGENCY

    // ── Couleurs (lazy pour contexte disponible) ─────────────────────────────
    private val colorActive   by lazy { requireContext().getColor(R.color.dash_accent_dim) }
    private val colorInactive by lazy { requireContext().getColor(R.color.dash_btn) }
    private val colorTextActive   by lazy { requireContext().getColor(R.color.dash_accent) }
    private val colorTextInactive by lazy { requireContext().getColor(R.color.text_secondary) }
    private val colorEcoBg   by lazy { requireContext().getColor(R.color.dash_eco_dim) }
    private val colorEcoText by lazy { requireContext().getColor(R.color.dash_eco) }
    private val colorWarnBg  by lazy { requireContext().getColor(R.color.dash_warn_dim) }
    private val colorWarnText by lazy { requireContext().getColor(R.color.dash_warn) }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPager(view)
    }

    override fun onResume() {
        super.onResume()
        refreshDriveRegen()
        refreshClimate()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()  // ELK utilise sVsm sur ces firmwares
            }
        }
        refreshElk()  // SWI133 — sVsm133 indépendant de Katman4
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ViewPager2 — Adapter + Dots
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupPager(root: View) {
        pager = root.findViewById(R.id.dashboard_pager)
        val dotsContainer = root.findViewById<LinearLayout>(R.id.pager_dots)

        pager?.adapter = DashboardPagerAdapter()
        pager?.offscreenPageLimit = 1  // garde les 2 pages en mémoire

        // Dots
        val dotCount = 2
        val dotViews = Array(dotCount) { i ->
            View(requireContext()).apply {
                val size = 10
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(6, 0, 6, 0)
                layoutParams = lp
                setBackgroundResource(R.drawable.dot_indicator)
                isSelected = i == 0
            }
        }
        dotViews.forEach { dotsContainer.addView(it) }
        dots = dotViews

        pager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots?.forEachIndexed { i, dot -> dot.isSelected = i == position }
                // Refresh ELK quand on arrive sur la page 1
                if (position == 1) refreshElk()
            }
        })
    }

    private inner class DashboardPagerAdapter :
        RecyclerView.Adapter<DashboardPagerAdapter.PageHolder>() {

        inner class PageHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun getItemCount() = 2
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val layoutId = if (viewType == 0) R.layout.page_dashboard_main
                           else               R.layout.page_dashboard_elk
            val v = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
            return PageHolder(v)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            when (position) {
                0 -> bindMainPage(holder.view)
                1 -> bindElkPage(holder.view)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 0 — Binding, visibility, listeners  (code existant)
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindMainPage(view: View) {
        bindMainViews(view)
        applyFirmwareVisibility(view)
        setupMainListeners()
        // Refresh immédiat (la page vient d'être créée)
        refreshDriveRegen()
        refreshClimate()
        MG4Hardware.whenKatman4Ready {
            if (isAdded) {
                refreshAdas()
                if (FirmwareInfo.isVsmBased()) refreshElk()
            }
        }
    }

    private fun bindMainViews(view: View) {
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

        // AEB déplacé sur page 1 pour tous les firmwares — pas de binding ici
    }

    private fun applyFirmwareVisibility(view: View) {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

        view.findViewById<View>(R.id.adas_group_swi133).visibility   = if (!isVsmBased) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.adas_group_swi68).visibility    = if (isVsmBased)  View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi133).visibility = if (!isVsmBased) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.alerts_group_swi68).visibility  = if (isVsmBased)  View.VISIBLE else View.GONE
        // AEB déplacé sur page 1 pour tous les firmwares
        view.findViewById<View>(R.id.aeb_group).visibility           = View.GONE
        view.findViewById<View>(R.id.climate_card).visibility        = if (hasClimate) View.VISIBLE else View.GONE
    }

    private fun setupMainListeners() {
        val gen        = FirmwareInfo.getGeneration()
        val isVsmBased = FirmwareInfo.isVsmBased()
        val isKnown    = gen != FirmwareInfo.Gen.UNKNOWN
        val hasClimate = FirmwareInfo.hasHeatFeatures()

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
        if (!isVsmBased) {
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

        // Climat
        if (hasClimate) {
            switchSteering?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSteeringHeat(checked) }
            }
            seatLeftButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatLeft(level) }
            } }
            seatRightButtons?.let { setupSeatButtons(it) { level ->
                CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSeatHeatRight(level) }
            } }
        }

        // Alertes SWI133
        if (!isVsmBased) {
            switchOverspeed?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setOverspeedAlarm(checked) }
            }
            switchSpeedTone?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    CoroutineScope(Dispatchers.IO).launch { MG4Hardware.setSpeedLimitTone(checked) }
            }
        }

        // Alerte sonore SWI68
        if (isVsmBased) {
            switchSoundWarning?.setOnCheckedChangeListener { _, checked ->
                if (!isRefreshing)
                    MG4Hardware.whenKatman4Ready { MG4Hardware.setSoundWarning(checked) }
            }
        }

        // AEB : listeners sur page 1 via setupAebPage2Listeners() — rien ici
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Page 1 — ELK : Binding + Listeners
    // ═════════════════════════════════════════════════════════════════════════

    private fun bindElkPage(view: View) {
        // ELK
        switchElk         = view.findViewById(R.id.switch_elk)
        btnElkAlert       = view.findViewById(R.id.btn_elk_alert)
        btnElkAssist      = view.findViewById(R.id.btn_elk_assist)
        btnElkEmergency   = view.findViewById(R.id.btn_elk_emergency)
        btnElkSenLow      = view.findViewById(R.id.btn_elk_sen_low)
        btnElkSenStandard = view.findViewById(R.id.btn_elk_sen_standard)
        btnElkSenHigh     = view.findViewById(R.id.btn_elk_sen_high)

        // AEB — page 1 pour tous les firmwares connus
        val aebCard = view.findViewById<View>(R.id.aeb_card_page2)
        if (FirmwareInfo.getGeneration() != FirmwareInfo.Gen.UNKNOWN) {
            aebCard.visibility    = View.VISIBLE
            switchAeb             = view.findViewById(R.id.switch_aeb_p2)
            btnAebAlarm           = view.findViewById(R.id.btn_aeb_alarm_p2)
            btnAebAlarmBrake      = view.findViewById(R.id.btn_aeb_alarm_brake_p2)
            btnAebSenLow          = view.findViewById(R.id.btn_aeb_sen_low)
            btnAebSenStandard     = view.findViewById(R.id.btn_aeb_sen_standard)
            btnAebSenHigh         = view.findViewById(R.id.btn_aeb_sen_high)
            setupAebPage2Listeners()
            MG4Hardware.whenKatman4Ready { if (isAdded) refreshAebPage2() }
        }

        setupElkListeners()
        refreshElk()
    }

    private fun setupElkListeners() {
        // Toggle ON/OFF
        switchElk?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                val mode = if (checked) lastActiveElkMode else ElkMode.OFF
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkMode(mode)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            applyElkModeUI(mode)
                            applyElkButtonsEnabled(checked)
                        }
                    }
                }
            }
        }

        // Mode buttons
        elkModeMap.forEach { (mode, btn) ->
            btn?.setOnClickListener {
                lastActiveElkMode = mode
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkMode(mode)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkModeUI(mode) }
                }
            }
        }

        // Sensitivity buttons
        elkSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setElkSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyElkSensitivityUI(level) }
                }
            }
        }
    }

    private fun setupAebPage2Listeners() {
        // Toggle ON/OFF
        switchAeb?.setOnCheckedChangeListener { _, checked ->
            if (!isRefreshing) {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebEnabled(checked)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebModeButtonsEnabled(checked) }
                }
            }
        }
        // Mode
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
        // Sensibilité
        aebSenMap.forEach { (level, btn) ->
            btn?.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    MG4Hardware.setAebSensitivity(level)
                    withContext(Dispatchers.Main) { if (isAdded) applyAebSensitivityUI(level) }
                }
            }
        }
    }

    private fun refreshAebPage2() {
        if (switchAeb == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val aebOn  = MG4Hardware.isAebEnabled()
            val aebMode = MG4Hardware.getAebMode()
            val aebSen  = MG4Hardware.getAebSensitivity()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                isRefreshing = true
                switchAeb?.isChecked = aebOn
                isRefreshing = false
                applyAebModeButtonsEnabled(aebOn)
                if (aebMode > 0) applyAebModeUI(aebMode)
                if (aebSen > 0) applyAebSensitivityUI(aebSen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Refresh depuis le hardware
    // ═════════════════════════════════════════════════════════════════════════

    private fun refreshDriveRegen() {
        if (driveModeButtons.isEmpty()) return  // page pas encore créée
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
        if (!FirmwareInfo.hasHeatFeatures() || switchSteering == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val steeringOn = MG4Hardware.isSteeringHeatOn()
            val leftLevel  = MG4Hardware.getSeatHeatLeft()
            val rightLevel = MG4Hardware.getSeatHeatRight()
            val ready = MG4Hardware.getIntPropertyHvac(MG4Hardware.PROP_SEAT_HEAT_L, 0x75) >= 0
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (ready) {
                    isRefreshing = true
                    switchSteering?.isChecked = steeringOn
                    isRefreshing = false
                    seatLeftButtons?.let  { applySeatUI(it, leftLevel) }
                    seatRightButtons?.let { applySeatUI(it, rightLevel) }
                } else {
                    view?.postDelayed({ if (isAdded) refreshClimate() }, 3_000)
                }
            }
        }
    }

    private fun refreshAdas() {
        // Vérifie que les boutons ADAS de page 0 sont créés (AEB est sur page 1)
        if (!FirmwareInfo.isVsmBased() && btnAdasOff == null) return
        if (FirmwareInfo.isVsmBased() && btnSwi68Off == null) return
        CoroutineScope(Dispatchers.IO).launch {
            if (FirmwareInfo.isVsmBased()) refreshSwi68Adas() else refreshSwi133Adas()
        }
    }

    private suspend fun refreshSwi133Adas() {
        val adasMode  = MG4Hardware.getMixedIntelligentDrive()
        val overspeed = MG4Hardware.isOverspeedAlarmOn()
        val speedTone = MG4Hardware.isSpeedLimitToneOn()
        val aebOn     = MG4Hardware.isAebEnabled()
        val aebMode   = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (adasMode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchOverspeed?.isChecked = overspeed
            switchSpeedTone?.isChecked = speedTone
            switchAeb?.isChecked = aebOn
            isRefreshing = false
            applySwi133AdasUI(adasMode)
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private suspend fun refreshSwi68Adas() {
        val mode    = MG4Hardware.getAccTjaMode()
        val sound   = MG4Hardware.isSoundWarningOn()
        val aebOn   = MG4Hardware.isAebEnabled()
        val aebMode = MG4Hardware.getAebMode()
        withContext(Dispatchers.Main) {
            if (!isAdded) return@withContext
            if (mode < 0) {
                view?.postDelayed({ if (isAdded) refreshAdas() }, 2_000)
                return@withContext
            }
            isRefreshing = true
            switchSoundWarning?.isChecked = sound
            switchAeb?.isChecked = aebOn
            isRefreshing = false
            applySwi68AdasUI(mode)
            applyAebModeButtonsEnabled(aebOn)
            if (aebMode > 0) applyAebModeUI(aebMode)
        }
    }

    private fun refreshElk() {
        if (switchElk == null) return  // page pas encore créée
        CoroutineScope(Dispatchers.IO).launch {
            val mode = MG4Hardware.getElkMode()
            val sen  = MG4Hardware.getElkSensitivity()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val enabled = mode > 0 && mode != ElkMode.OFF
                if (enabled) lastActiveElkMode = mode
                isRefreshing = true
                switchElk?.isChecked = enabled
                isRefreshing = false
                applyElkModeUI(mode)
                applyElkButtonsEnabled(enabled)
                if (sen > 0) applyElkSensitivityUI(sen)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 0
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyDriveModeUI(mode: DriveMode) {
        driveModeButtons.forEach { (m, btn) ->
            val (bg, text) = when {
                m != mode            -> colorInactive to colorTextInactive
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

    private fun applyAebModeUI(activeMode: Int) {
        btnAebAlarm?.backgroundTintList      = ColorStateList.valueOf(if (activeMode == AebMode.ALARM)       colorActive else colorInactive)
        btnAebAlarm?.setTextColor(                                    if (activeMode == AebMode.ALARM)       colorTextActive else colorTextInactive)
        btnAebAlarmBrake?.backgroundTintList = ColorStateList.valueOf(if (activeMode == AebMode.ALARM_BRAKE) colorActive else colorInactive)
        btnAebAlarmBrake?.setTextColor(                               if (activeMode == AebMode.ALARM_BRAKE) colorTextActive else colorTextInactive)
    }

    private fun applyAebModeButtonsEnabled(enabled: Boolean) {
        btnAebAlarm?.isEnabled      = enabled
        btnAebAlarmBrake?.isEnabled = enabled
        btnAebAlarm?.alpha          = if (enabled) 1f else 0.35f
        btnAebAlarmBrake?.alpha     = if (enabled) 1f else 0.35f
    }

    private fun applySeatUI(buttons: List<Button>, activeIndex: Int) {
        buttons.forEachIndexed { i, btn ->
            val active = i == activeIndex
            btn.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers UI — Page 1 (ELK)
    // ═════════════════════════════════════════════════════════════════════════

    private fun applyElkModeUI(activeMode: Int) {
        elkModeMap.forEach { (mode, btn) ->
            val active = mode == activeMode
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkSensitivityUI(activeLevel: Int) {
        elkSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyAebSensitivityUI(activeLevel: Int) {
        aebSenMap.forEach { (level, btn) ->
            val active = level == activeLevel
            btn?.backgroundTintList = ColorStateList.valueOf(if (active) colorActive else colorInactive)
            btn?.setTextColor(if (active) colorTextActive else colorTextInactive)
        }
    }

    private fun applyElkButtonsEnabled(enabled: Boolean) {
        (elkModeMap.values + elkSenMap.values).forEach { btn ->
            btn?.isEnabled = enabled
            btn?.alpha = if (enabled) 1f else 0.35f
        }
    }
}
