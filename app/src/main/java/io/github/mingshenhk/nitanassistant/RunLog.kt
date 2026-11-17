package io.github.mingshenhk.nitanassistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RunLog {
    private const val LIMIT = 100
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
    private var entries = emptyList<String>()
    private var loaded = false
    var lastOperation = "—"
        private set
    var lastError = "—"
        private set
    var revision = 0
        private set

    fun read(context: Context): List<String> {
        if (!loaded) {
            val store = context.getSharedPreferences("run-log", Context.MODE_PRIVATE)
            entries = store.getString("entries", null)?.lineSequence()?.takeLastEntries() ?: emptyList()
            lastOperation = store.getString("operation", "—") ?: "—"
            lastError = store.getString("error", "—") ?: "—"
            loaded = true
        }
        return entries
    }

    fun append(context: Context, task: String, level: Level, message: String) {
        read(context)
        val time = format.format(Date())
        entries = (entries + "$time ${level.name} [$task] $message").takeLast(LIMIT)
        if (level == Level.INFO) lastOperation = message
        if (level == Level.ERROR) lastError = message
        context.getSharedPreferences("run-log", Context.MODE_PRIVATE).edit()
            .putString("entries", entries.joinToString("\n"))
            .putString("operation", lastOperation)
            .putString("error", lastError)
            .apply()
        revision++
    }

    private fun Sequence<String>.takeLastEntries() = toList().takeLast(LIMIT)
}
