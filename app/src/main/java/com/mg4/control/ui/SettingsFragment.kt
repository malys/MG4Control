package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.mg4.control.R
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

        // ── Bouton Infos ─────────────────────────────────────────────────────
        view.findViewById<MaterialButton>(R.id.btn_infos).setOnClickListener {
            showInfosDialog()
        }
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

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.info_title)
            .setView(dialogView)
            .setPositiveButton(R.string.info_close, null)
            .show()
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
