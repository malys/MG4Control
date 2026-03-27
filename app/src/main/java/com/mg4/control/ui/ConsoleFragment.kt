package com.mg4.control.ui

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mg4.control.R
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware

class ConsoleFragment : Fragment() {

    private lateinit var textStatus: TextView
    private lateinit var textLog: TextView
    private lateinit var scrollView: ScrollView

    private val logListener: () -> Unit = {
        activity?.runOnUiThread { if (isAdded) renderLog() }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_console, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textStatus = view.findViewById(R.id.console_status)
        textLog    = view.findViewById(R.id.console_text)
        scrollView = view.findViewById(R.id.console_scroll)

        view.findViewById<Button>(R.id.btn_clear_console).setOnClickListener {
            AppLogger.clear()
        }

        renderStatus()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        renderStatus()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
    }

    // ---- Status banner ----

    private fun renderStatus() {
        val sb = SpannableStringBuilder()

        fun appendStatus(label: String, ok: Boolean) {
            val line = if (ok) "  ✓  $label\n" else "  ✗  $label\n"
            val color = if (ok) Color.parseColor("#AAFFAA") else Color.parseColor("#FF5555")
            val start = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
        }

        appendStatus("Katman1 — CarPropertyManager", MG4Hardware.isCarPropertyManagerReady())
        appendStatus("Katman1 — CarHvacManager",     MG4Hardware.isCarHvacManagerReady())
        appendStatus("Katman4 — VPM créé",           MG4Hardware.isKatman4VpmCreated())
        appendStatus("Katman4 — Service connecté",   MG4Hardware.isKatman4Ready())

        textStatus.text = sb
    }

    // ---- Log list ----

    private fun renderLog() {
        val sb = SpannableStringBuilder()
        AppLogger.entries.forEach { entry ->
            val prefix = "[${entry.time}] "
            val tag    = "${entry.tag}: "
            val msg    = "${entry.msg}\n"
            val color  = when (entry.level) {
                AppLogger.Level.ERROR -> Color.parseColor("#FF5555")
                AppLogger.Level.WARN  -> Color.parseColor("#FFAA00")
                AppLogger.Level.DEBUG -> Color.parseColor("#888888")
                AppLogger.Level.INFO  -> Color.parseColor("#CCCCCC")
            }
            val start = sb.length
            sb.append(prefix).append(tag).append(msg)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#666666")), start, start + prefix.length, 0)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#AAAAFF")), start + prefix.length, start + prefix.length + tag.length, 0)
            sb.setSpan(ForegroundColorSpan(color), start + prefix.length + tag.length, sb.length, 0)
        }
        textLog.text = sb
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
