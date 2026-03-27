package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.mg4.control.R
import com.mg4.control.update.UpdateChecker
import com.mg4.control.update.UpdateDialogManager
import com.mg4.control.util.FirmwareHelper
import com.mg4.control.util.LocaleHelper

class SettingsFragment : Fragment() {

    private val githubUrl = "https://github.com/SliDeeN/MG4Control"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("mg4_settings", Context.MODE_PRIVATE)
        val accentColor  = requireContext().getColor(R.color.dash_accent)
        val accentDim    = requireContext().getColor(R.color.dash_accent_dim)
        val inactiveColor = requireContext().getColor(R.color.dash_btn)
        val textActive    = requireContext().getColor(R.color.dash_accent)
        val textInactive  = requireContext().getColor(R.color.text_secondary)

        // ── Langue ───────────────────────────────────────────────────────────
        val btnFr = view.findViewById<MaterialButton>(R.id.btn_lang_fr)
        val btnEn = view.findViewById<MaterialButton>(R.id.btn_lang_en)

        fun updateLangButtons(lang: String) {
            val isFr = lang == "fr"
            btnFr.backgroundTintList  = ColorStateList.valueOf(if (isFr) accentDim   else inactiveColor)
            btnFr.setTextColor(if (isFr) textActive else textInactive)
            btnEn.backgroundTintList  = ColorStateList.valueOf(if (!isFr) accentDim  else inactiveColor)
            btnEn.setTextColor(if (!isFr) textActive else textInactive)
        }

        updateLangButtons(LocaleHelper.getLanguage(requireContext()))

        btnFr.setOnClickListener {
            if (LocaleHelper.getLanguage(requireContext()) != "fr") {
                LocaleHelper.setLanguage(requireContext(), "fr")
                requireActivity().recreate()
            }
        }
        btnEn.setOnClickListener {
            if (LocaleHelper.getLanguage(requireContext()) != "en") {
                LocaleHelper.setLanguage(requireContext(), "en")
                requireActivity().recreate()
            }
        }

        // ── Auto-apply ───────────────────────────────────────────────────────
        val switchAutoApply = view.findViewById<Switch>(R.id.switch_auto_apply)
        switchAutoApply.isChecked = prefs.getBoolean("auto_apply_profile", true)
        switchAutoApply.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_apply_profile", checked).apply()
        }

        // ── Bouton Vérifier mise à jour ──────────────────────────────────────
        val btnUpdate = view.findViewById<MaterialButton>(R.id.btn_check_update)
        val originalUpdateText = getString(R.string.btn_check_update)

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false

            UpdateChecker.check(
                context = requireContext(),
                onUpdateAvailable = { updateInfo ->
                    if (isAdded) {
                        btnUpdate.isEnabled = true
                        UpdateDialogManager.show(
                            requireActivity() as androidx.appcompat.app.AppCompatActivity,
                            updateInfo
                        )
                    }
                },
                onNoUpdate = {
                    if (isAdded) showUpToDate(btnUpdate, originalUpdateText)
                },
                onError = {
                    if (isAdded) showUpdateError(btnUpdate, originalUpdateText)
                }
            )
        }

        // ── Bouton Nettoyer APK ──────────────────────────────────────────────
        val btnClean = view.findViewById<MaterialButton>(R.id.btn_clean_apk)
        val originalCleanText = getString(R.string.btn_clean_apk)

        btnClean.setOnClickListener {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val apkFiles = downloadsDir.listFiles { _, name ->
                name.startsWith("MGControl") && name.endsWith(".apk")
            } ?: emptyArray()

            val count = apkFiles.count { it.delete() }

            btnClean.isEnabled = false
            if (count > 0) {
                btnClean.text = getString(R.string.clean_apk_done, count)
                btnClean.backgroundTintList = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco_dim))
                btnClean.strokeColor = ColorStateList.valueOf(
                    requireContext().getColor(R.color.dash_eco))
                btnClean.setTextColor(requireContext().getColor(R.color.dash_eco))
            } else {
                btnClean.text = getString(R.string.clean_apk_none)
            }

            btnClean.postDelayed({
                if (isAdded) {
                    btnClean.text = originalCleanText
                    btnClean.backgroundTintList = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_btn))
                    btnClean.strokeColor = ColorStateList.valueOf(
                        requireContext().getColor(R.color.dash_border))
                    btnClean.setTextColor(requireContext().getColor(R.color.text_secondary))
                    btnClean.isEnabled = true
                }
            }, 3_000)
        }

        // ── Bouton Infos ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_infos).setOnClickListener {
            showInfosDialog()
        }

        // ── Bouton Fermer ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_close_settings).setOnClickListener {
            findNavController().popBackStack()
        }
    }

    // ── Feedback "application à jour" sur le bouton ──────────────────────────

    private fun showUpToDate(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val ecoDim    = ctx.getColor(R.color.dash_eco_dim)
        val eco       = ctx.getColor(R.color.dash_eco)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        // Passe le bouton en vert "à jour"
        btn.text = getString(R.string.update_up_to_date)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(ecoDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(eco)
        btn.setTextColor(eco)
        btn.isEnabled = false

        // Revient à l'état normal après 3 secondes
        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Feedback "erreur réseau" sur le bouton ────────────────────────────────

    private fun showUpdateError(btn: MaterialButton, originalText: String) {
        val ctx = requireContext()
        val dangerDim = ctx.getColor(R.color.dash_danger_dim)
        val danger    = ctx.getColor(R.color.dash_danger)
        val accentDim = ctx.getColor(R.color.dash_accent_dim)
        val accent    = ctx.getColor(R.color.dash_accent)

        btn.text = getString(R.string.update_network_error)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(dangerDim)
        btn.strokeColor        = android.content.res.ColorStateList.valueOf(danger)
        btn.setTextColor(danger)
        btn.isEnabled = false

        btn.postDelayed({
            if (isAdded) {
                btn.text = originalText
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(accentDim)
                btn.strokeColor        = android.content.res.ColorStateList.valueOf(accent)
                btn.setTextColor(accent)
                btn.isEnabled = true
            }
        }, 3_000)
    }

    // ── Dialog À propos ──────────────────────────────────────────────────────

    private fun showInfosDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_app_info, null)

        // Version de l'app
        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        dialogView.findViewById<TextView>(R.id.tv_app_version).text = versionName

        // Version firmware (lecture asynchrone)
        val tvFirmware = dialogView.findViewById<TextView>(R.id.tv_firmware_info)
        FirmwareHelper.getMpuVersion(requireContext()) { version ->
            requireActivity().runOnUiThread {
                if (isAdded) tvFirmware.text = version ?: "N/A"
            }
        }

        // QR Code GitHub
        val ivQr = dialogView.findViewById<ImageView>(R.id.iv_qr_code)
        generateQrBitmap(githubUrl, 400)?.let { ivQr.setImageBitmap(it) }

        // Lien GitHub cliquable
        dialogView.findViewById<TextView>(R.id.tv_github_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
        }

        // Création du dialog sans chrome Android (fond transparent = layout seul visible)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Bouton Fermer intégré dans le layout
        dialogView.findViewById<MaterialButton>(R.id.btn_info_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Génération QR code (ZXing core) ──────────────────────────────────────

    private fun generateQrBitmap(content: String, sizePx: Int = 400): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
