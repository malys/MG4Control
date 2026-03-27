package com.mg4.control.debug

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app log buffer — mirrors every Log.* call to an in-memory ring buffer
 * so the ConsoleFragment can display them without ADB.
 */
object AppLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val time: String,
        val tag: String,
        val level: Level,
        val msg: String
    )

    private const val MAX_ENTRIES = 400
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    val entries = CopyOnWriteArrayList<Entry>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---- Public log methods (mirror android.util.Log) ----

    fun d(tag: String, msg: String) { add(tag, Level.DEBUG, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { add(tag, Level.INFO,  msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { add(tag, Level.WARN,  msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { add(tag, Level.ERROR, msg); Log.e(tag, msg) }

    fun clear() {
        entries.clear()
        notifyListeners()
    }

    // ---- Listener for live UI updates ----

    fun addListener(l: () -> Unit)    { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    // ---- Internal ----

    private fun add(tag: String, level: Level, msg: String) {
        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
        entries.add(Entry(sdf.format(Date()), tag, level, msg))
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { try { it.invoke() } catch (_: Exception) {} }
    }
}
