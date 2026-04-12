package com.mg4.control.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.MG4Hardware.AebMode
import com.mg4.control.hardware.MG4Hardware.AebSensitivity
import com.mg4.control.hardware.MG4Hardware.ElkMode
import com.mg4.control.hardware.MG4Hardware.ElkSensitivity
import com.mg4.control.hardware.MG4Hardware.Swi68Mode
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.RegenLevel
import com.mg4.control.profile.ProfileApplier
import com.mg4.control.profile.ProfileManager
import com.mg4.control.util.FirmwareInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var manager: ProfileManager
    private lateinit var adapter: ProfileAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_profile, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        manager = ProfileManager(requireContext())

        adapter = ProfileAdapter(
            mutableListOf(),
            manager.getDefaultId(),
            onApply = { profile ->
                ProfileApplier.apply(profile) { ok ->
                    requireActivity().runOnUiThread {
                        val msg = if (ok) getString(R.string.profile_applied, profile.name)
                                  else "Profil appliqué (vérifier les logs)"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetDefault = { profile ->
                manager.setDefault(profile.id)
                refreshList()
                Toast.makeText(context, "Profil par défaut : ${profile.name}", Toast.LENGTH_SHORT).show()
            },
            onEdit = { profile ->
                showProfileDialog(existing = profile, data = profile)
            },
            onDelete = { profile ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Supprimer \"${profile.name}\" ?")
                    .setPositiveButton(R.string.profile_delete) { _, _ ->
                        manager.delete(profile.id)
                        refreshList()
                    }
                    .setNegativeButton(R.string.profile_cancel, null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.recycler_profiles).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ProfileFragment.adapter
        }

        // ── Bouton Fermer ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_close_profiles).setOnClickListener {
            findNavController().popBackStack()
        }

        view.findViewById<View>(R.id.btn_add_profile).setOnClickListener {
            if (manager.getAll().size >= ProfileManager.MAX_PROFILES) {
                Toast.makeText(context, getString(R.string.profile_max_reached, ProfileManager.MAX_PROFILES), Toast.LENGTH_SHORT).show()
            } else {
                openNewProfileDialog()
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.update(manager.getAll(), manager.getDefaultId())
    }

    // -------------------------------------------------------------------------
    // Nouveau profil : lit l'état hardware courant puis ouvre le dialog pré-rempli
    // -------------------------------------------------------------------------

    private fun openNewProfileDialog() {
        CoroutineScope(Dispatchers.IO).launch {
            val hasHeat = FirmwareInfo.hasHeatFeatures()
            val prefill = if (FirmwareInfo.isVsmBased()) {
                // SWI68/SWI69/SWI131/SWI165 : ADAS ACC/TJA — sièges/volant uniquement sur SWI68/SWI165
                val elkMode = MG4Hardware.getElkMode().let { if (it < 1) ElkMode.EMERGENCY else it }
                val elkSen  = MG4Hardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                DrivingProfile(
                    name          = "",
                    driveMode     = MG4Hardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel    = MG4Hardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat  = if (hasHeat) MG4Hardware.isSteeringHeatOn() else false,
                    seatHeatLeft  = if (hasHeat) MG4Hardware.getSeatHeatLeft().coerceAtLeast(0) else 0,
                    seatHeatRight = if (hasHeat) MG4Hardware.getSeatHeatRight().coerceAtLeast(0) else 0,
                    soundWarning  = MG4Hardware.isSoundWarningOn(),
                    swi68AdasMode = MG4Hardware.getAccTjaMode().let { if (it < 0) Swi68Mode.OFF else it },
                    aebEnabled     = MG4Hardware.isAebEnabled(),
                    aebMode        = MG4Hardware.getAebMode().let { if (it < 1) AebMode.ALARM else it },
                    aebSensitivity = MG4Hardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it },
                    elkMode        = elkMode,
                    elkSensitivity = elkSen
                )
            } else {
                // SWI133/UNKNOWN : ADAS mixte, sièges et volant chauffants
                val elkMode = MG4Hardware.getElkMode().let { if (it < 1) ElkMode.EMERGENCY else it }
                val elkSen  = MG4Hardware.getElkSensitivity().let { if (it < 1) ElkSensitivity.STANDARD else it }
                val aebSen  = MG4Hardware.getAebSensitivity().let { if (it < 1) AebSensitivity.STANDARD else it }
                DrivingProfile(
                    name           = "",
                    driveMode      = MG4Hardware.getDriveMode()  ?: DriveMode.NORMAL,
                    regenLevel     = MG4Hardware.getRegenLevel() ?: RegenLevel.MEDIUM,
                    steeringHeat   = MG4Hardware.isSteeringHeatOn(),
                    seatHeatLeft   = MG4Hardware.getSeatHeatLeft().coerceAtLeast(0),
                    seatHeatRight  = MG4Hardware.getSeatHeatRight().coerceAtLeast(0),
                    overspeedAlarm = MG4Hardware.isOverspeedAlarmOn(),
                    speedLimitTone = MG4Hardware.isSpeedLimitToneOn(),
                    adasMode       = MG4Hardware.getMixedIntelligentDrive().coerceAtLeast(0),
                    aebEnabled     = MG4Hardware.isAebEnabled(),
                    aebMode        = MG4Hardware.getAebMode().let { if (it < 1) AebMode.ALARM else it },
                    aebSensitivity = aebSen,
                    elkMode        = elkMode,
                    elkSensitivity = elkSen
                )
            }
            withContext(Dispatchers.Main) {
                if (isAdded) showProfileDialog(existing = null, data = prefill)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dialog d'édition / création — style dark MaterialButton
    // -------------------------------------------------------------------------

    private fun showProfileDialog(existing: DrivingProfile?, data: DrivingProfile) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_profile_edit, null)
        val gen = FirmwareInfo.getGeneration()

        // ── Couleurs ─────────────────────────────────────────────────────────
        fun activateBtn(btn: MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent_dim else R.color.dash_btn))
            btn.setTextColor(ctx.getColor(
                if (active) R.color.dash_accent else R.color.text_secondary))
            btn.strokeColor = ColorStateList.valueOf(
                ctx.getColor(if (active) R.color.dash_accent else R.color.dash_border))
        }

        /** Lie un groupe de boutons : un seul actif à la fois. Retourne une lambda pour lire la valeur courante. */
        fun <T> bindGroup(pairs: List<Pair<MaterialButton, T>>, initial: T, onSelect: (T) -> Unit) {
            pairs.forEach { (btn, value) -> activateBtn(btn, value == initial) }
            pairs.forEach { (btn, value) ->
                btn.setOnClickListener {
                    pairs.forEach { (b, v) -> activateBtn(b, v == value) }
                    onSelect(value)
                }
            }
        }

        // ── Variables de sélection ───────────────────────────────────────────
        var selectedDrive   = data.driveMode
        var selectedRegen   = data.regenLevel
        var steeringOn      = data.steeringHeat
        var seatLeft        = data.seatHeatLeft
        var seatRight       = data.seatHeatRight
        var adasMode        = data.adasMode
        var swi68Mode       = data.swi68AdasMode
        var aebEnabledSel   = data.aebEnabled
        var aebModeSel      = data.aebMode
        var aebSenSel       = data.aebSensitivity.let { if (it == 0) AebSensitivity.STANDARD else it }
        var elkModeSel      = data.elkMode.let { if (it == 0) ElkMode.EMERGENCY else it }
        var elkSenSel       = data.elkSensitivity.let { if (it == 0) ElkSensitivity.STANDARD else it }
        var elkEnabledSel   = elkModeSel != ElkMode.OFF
        /** Dernier mode ELK actif pour restauration après toggle ON */
        var lastActiveElkModeD = if (elkModeSel != ElkMode.OFF) elkModeSel else ElkMode.EMERGENCY

        // ── Mode de conduite ─────────────────────────────────────────────────
        val drivePairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_eco_d)    to DriveMode.ECO,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_normal_d) to DriveMode.NORMAL,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_sport_d)  to DriveMode.SPORT,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_snow_d)   to DriveMode.SNOW,
            dialogView.findViewById<MaterialButton>(R.id.btn_drive_custom_d) to DriveMode.CUSTOM
        )
        val regenSection = dialogView.findViewById<View>(R.id.section_regen_dialog)
        val regenBtns = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_off_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_low_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_medium_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_high_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d),
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d)
        )

        fun setRegenEnabled(enabled: Boolean) {
            regenBtns.forEach { btn ->
                btn.isEnabled = enabled
                btn.alpha = if (enabled) 1f else 0.35f
            }
        }

        bindGroup(drivePairs, selectedDrive) { mode ->
            selectedDrive = mode
            setRegenEnabled(mode != DriveMode.SNOW)
        }
        setRegenEnabled(data.driveMode != DriveMode.SNOW)

        // ── Régénération ─────────────────────────────────────────────────────
        val regenPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_off_d)       to RegenLevel.OFF,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_low_d)       to RegenLevel.LOW,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_medium_d)    to RegenLevel.MEDIUM,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_high_d)      to RegenLevel.HIGH,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_adaptive_d)  to RegenLevel.ADAPTIVE,
            dialogView.findViewById<MaterialButton>(R.id.btn_regen_one_pedal_d) to RegenLevel.ONE_PEDAL
        )
        bindGroup(regenPairs, selectedRegen) { selectedRegen = it }

        // ── Volant chauffant ─────────────────────────────────────────────────
        val steerPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_steer_off_d) to false,
            dialogView.findViewById<MaterialButton>(R.id.btn_steer_on_d)  to true
        )
        bindGroup(steerPairs, steeringOn) { steeringOn = it }

        // ── Siège gauche ─────────────────────────────────────────────────────
        val seatLeftPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_0_d) to 0,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_1_d) to 1,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_2_d) to 2,
            dialogView.findViewById<MaterialButton>(R.id.btn_sl_3_d) to 3
        )
        bindGroup(seatLeftPairs, seatLeft) { seatLeft = it }

        // ── Siège droit ──────────────────────────────────────────────────────
        val seatRightPairs = listOf(
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_0_d) to 0,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_1_d) to 1,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_2_d) to 2,
            dialogView.findViewById<MaterialButton>(R.id.btn_sr_3_d) to 3
        )
        bindGroup(seatRightPairs, seatRight) { seatRight = it }

        // ── Sections Climat (Volant + Sièges) — masquées si pas de chauffage (SWI69/SWI131) ─
        val hasHeat = FirmwareInfo.hasHeatFeatures()
        dialogView.findViewById<View>(R.id.section_steering_dialog)?.visibility =
            if (hasHeat) View.VISIBLE else View.GONE
        dialogView.findViewById<View>(R.id.section_seats_dialog)?.visibility =
            if (hasHeat) View.VISIBLE else View.GONE

        // ── Section AEB (commune SWI133 + SWI68 + SWI69) ────────────────────
        val sectionAeb = dialogView.findViewById<View>(R.id.adas_section_aeb)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionAeb.visibility = View.VISIBLE
            val swAeb         = dialogView.findViewById<Switch>(R.id.sw_aeb_enabled)
            val btnAebAlarmD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_alarm_d)
            val btnAebBrakeD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_alarm_brake_d)

            // Sensibilité AEB — SWI133 uniquement
            val aebSenSectionD = dialogView.findViewById<View>(R.id.aeb_sen_section_d)
            val btnAebSenLowD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_low_d)
            val btnAebSenStdD  = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_standard_d)
            val btnAebSenHighD = dialogView.findViewById<MaterialButton>(R.id.btn_aeb_sen_high_d)

            val showSensitivity = gen != FirmwareInfo.Gen.UNKNOWN
            aebSenSectionD.visibility = if (showSensitivity) View.VISIBLE else View.GONE

            fun setAebModeButtonsEnabled(enabled: Boolean) {
                listOf(btnAebAlarmD, btnAebBrakeD).forEach { btn ->
                    btn.isEnabled = enabled
                    btn.alpha     = if (enabled) 1f else 0.35f
                }
                if (showSensitivity) {
                    listOf(btnAebSenLowD, btnAebSenStdD, btnAebSenHighD).forEach { btn ->
                        btn.isEnabled = enabled
                        btn.alpha     = if (enabled) 1f else 0.35f
                    }
                }
            }

            swAeb.isChecked = aebEnabledSel
            setAebModeButtonsEnabled(aebEnabledSel)
            swAeb.setOnCheckedChangeListener { _, checked ->
                aebEnabledSel = checked
                setAebModeButtonsEnabled(checked)
            }

            val aebModePairs = listOf(btnAebAlarmD to AebMode.ALARM, btnAebBrakeD to AebMode.ALARM_BRAKE)
            bindGroup(aebModePairs, aebModeSel) { aebModeSel = it }

            if (showSensitivity) {
                val aebSenPairs = listOf(
                    btnAebSenLowD  to AebSensitivity.LOW,
                    btnAebSenStdD  to AebSensitivity.STANDARD,
                    btnAebSenHighD to AebSensitivity.HIGH
                )
                bindGroup(aebSenPairs, aebSenSel) { aebSenSel = it }
            }
        }

        // ── Section ELK (tous firmwares connus) ─────────────────────────────
        val sectionElk = dialogView.findViewById<View>(R.id.elk_section_dialog)
        if (gen != FirmwareInfo.Gen.UNKNOWN) {
            sectionElk.visibility = View.VISIBLE

            val swElk           = dialogView.findViewById<Switch>(R.id.sw_elk_enabled)
            val btnElkAlertD    = dialogView.findViewById<MaterialButton>(R.id.btn_elk_alert_d)
            val btnElkAssistD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_assist_d)
            val btnElkEmergD    = dialogView.findViewById<MaterialButton>(R.id.btn_elk_emergency_d)
            val btnElkSenLowD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_low_d)
            val btnElkSenStdD   = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_standard_d)
            val btnElkSenHighD  = dialogView.findViewById<MaterialButton>(R.id.btn_elk_sen_high_d)

            val elkModeBtns = listOf(btnElkAlertD, btnElkAssistD, btnElkEmergD)
            val elkSenBtns  = listOf(btnElkSenLowD, btnElkSenStdD, btnElkSenHighD)

            fun setElkButtonsEnabled(enabled: Boolean) {
                (elkModeBtns + elkSenBtns).forEach { btn ->
                    btn.isEnabled = enabled
                    btn.alpha     = if (enabled) 1f else 0.35f
                }
            }

            swElk.isChecked = elkEnabledSel
            setElkButtonsEnabled(elkEnabledSel)
            swElk.setOnCheckedChangeListener { _, checked ->
                elkEnabledSel = checked
                elkModeSel = if (checked) lastActiveElkModeD else ElkMode.OFF
                setElkButtonsEnabled(checked)
            }

            val elkModePairs = listOf(
                btnElkAlertD  to ElkMode.ALERT,
                btnElkAssistD to ElkMode.ASSIST,
                btnElkEmergD  to ElkMode.EMERGENCY
            )
            bindGroup(elkModePairs, if (elkEnabledSel) elkModeSel else ElkMode.EMERGENCY) { mode ->
                elkModeSel = mode
                lastActiveElkModeD = mode
            }

            val elkSenPairs = listOf(
                btnElkSenLowD  to ElkSensitivity.LOW,
                btnElkSenStdD  to ElkSensitivity.STANDARD,
                btnElkSenHighD to ElkSensitivity.HIGH
            )
            bindGroup(elkSenPairs, elkSenSel) { elkSenSel = it }
        }

        // ── Sections ADAS ────────────────────────────────────────────────────
        val sectionSwi133 = dialogView.findViewById<View>(R.id.adas_section_swi133)
        val sectionSwi68  = dialogView.findViewById<View>(R.id.adas_section_swi68)

        // SWI68/SWI69/SWI131 : même interface ADAS (ACC / TJA / Off + alerte sonore)
        if (FirmwareInfo.isVsmBased()) {
            sectionSwi68.visibility  = View.VISIBLE
            sectionSwi133.visibility = View.GONE

            // Alerte SWI68
            val swSoundWarning = dialogView.findViewById<Switch>(R.id.sw_sound_warning)
            swSoundWarning.isChecked = data.soundWarning

            // Mode ADAS SWI68
            val adasSwi68Pairs = listOf(
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_off_d) to Swi68Mode.OFF,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_acc_d) to Swi68Mode.ACC,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_swi68_tja_d) to Swi68Mode.TJA
            )
            bindGroup(adasSwi68Pairs, swi68Mode) { swi68Mode = it }

        } else {
            sectionSwi133.visibility = View.VISIBLE
            sectionSwi68.visibility  = View.GONE

            // Alertes SWI133
            val swOverspeed = dialogView.findViewById<Switch>(R.id.sw_overspeed_alarm)
            val swSpeedTone = dialogView.findViewById<Switch>(R.id.sw_speed_limit_tone)
            swOverspeed.isChecked = data.overspeedAlarm
            swSpeedTone.isChecked = data.speedLimitTone

            // Mode ADAS SWI133 : Off=0 / Lim=1 / Auto=2 / ACC=3 / ICA=4
            val adasSwi133Pairs = listOf(
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_off_d)  to 0,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_lim_d)  to 1,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_auto_d) to 2,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_acc_d)  to 3,
                dialogView.findViewById<MaterialButton>(R.id.btn_adas_ica_d)  to 4
            )
            bindGroup(adasSwi133Pairs, adasMode) { adasMode = it }
        }

        // ── Profil par défaut ────────────────────────────────────────────────
        val swDefault = dialogView.findViewById<Switch>(R.id.sw_set_default)
        swDefault.isChecked = existing?.id == manager.getDefaultId()

        // ── Nom ──────────────────────────────────────────────────────────────
        val etName = dialogView.findViewById<EditText>(R.id.et_profile_name)
        if (existing != null) etName.setText(existing.name)

        // ── Création du dialog sans chrome Android ───────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Titre dynamique intégré dans le layout
        dialogView.findViewById<TextView>(R.id.tv_dialog_title).text =
            if (existing != null) getString(R.string.profile_edit) else getString(R.string.profile_add)

        // ── Bouton Annuler ───────────────────────────────────────────────────
        dialogView.findViewById<MaterialButton>(R.id.btn_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }

        // ── Bouton Enregistrer : ne ferme PAS si le nom est vide ────────────
        dialogView.findViewById<MaterialButton>(R.id.btn_dialog_save).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = getString(R.string.profile_name_required)
                etName.requestFocus()
                return@setOnClickListener
            }

            val overspeedAlarm = dialogView.findViewById<Switch?>(R.id.sw_overspeed_alarm)?.isChecked ?: false
            val speedLimitTone = dialogView.findViewById<Switch?>(R.id.sw_speed_limit_tone)?.isChecked ?: false
            val soundWarning   = dialogView.findViewById<Switch?>(R.id.sw_sound_warning)?.isChecked ?: false

            val profile = DrivingProfile(
                id             = existing?.id ?: java.util.UUID.randomUUID().toString(),
                name           = name,
                driveMode      = selectedDrive,
                regenLevel     = selectedRegen,
                steeringHeat   = steeringOn,
                seatHeatLeft   = seatLeft,
                seatHeatRight  = seatRight,
                overspeedAlarm = overspeedAlarm,
                speedLimitTone = speedLimitTone,
                adasMode       = adasMode,
                soundWarning   = soundWarning,
                swi68AdasMode  = swi68Mode,
                aebEnabled     = aebEnabledSel,
                aebMode        = aebModeSel,
                aebSensitivity = aebSenSel,
                elkMode        = elkModeSel,
                elkSensitivity = elkSenSel
            )
            manager.save(profile)
            if (swDefault.isChecked) manager.setDefault(profile.id)
            refreshList()
            dialog.dismiss()
        }

        dialog.show()

        // Borner la hauteur du dialog : footer toujours visible même sur petit écran
        val maxH = (requireActivity().resources.displayMetrics.heightPixels * 0.88).toInt()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            maxH
        )
    }
}
