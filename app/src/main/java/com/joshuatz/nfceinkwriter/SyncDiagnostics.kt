package com.joshuatz.nfceinkwriter

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records recent NFC sync attempts for troubleshooting scrambled or partial panels. */
object SyncDiagnostics {

    private const val PREFS = "sync_diagnostics"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_ENTRIES = 12

    data class Entry(
        val timestampMs: Long,
        val success: Boolean,
        val message: String,
        val driverName: String,
        val maxProgress: Int,
        val durationMs: Long,
        val recoveryPattern: String?,
        val refreshStalled: Boolean,
        val needsPanelVerify: Boolean,
    )

    fun record(
        context: Context,
        success: Boolean,
        message: String,
        driverName: String,
        maxProgress: Int,
        durationMs: Long,
        recoveryPattern: PanelTestPattern? = null,
        refreshStalled: Boolean = false,
        needsPanelVerify: Boolean = false,
    ) {
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            success = success,
            message = message,
            driverName = driverName,
            maxProgress = maxProgress,
            durationMs = durationMs,
            recoveryPattern = recoveryPattern?.storageKey,
            refreshStalled = refreshStalled,
            needsPanelVerify = needsPanelVerify,
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = loadHistory(prefs.getString(KEY_HISTORY, "[]") ?: "[]").toMutableList()
        history.add(0, entry)
        while (history.size > MAX_ENTRIES) {
            history.removeAt(history.size - 1)
        }
        prefs.edit().putString(KEY_HISTORY, encodeHistory(history)).apply()
    }

    fun latestEntry(context: Context): Entry? = loadHistory(context).firstOrNull()

    fun loadHistory(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "[]") ?: "[]"
        return loadHistory(raw)
    }

    fun formatLatestSummary(context: Context): String {
        val latest = latestEntry(context) ?: return context.getString(R.string.troubleshoot_no_sync_history)
        return formatEntry(context, latest)
    }

    fun formatEntry(context: Context, entry: Entry): String {
        val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(entry.timestampMs))
        val outcome = if (entry.success) {
            context.getString(R.string.troubleshoot_outcome_success)
        } else {
            context.getString(R.string.troubleshoot_outcome_failed)
        }
        val pattern = entry.recoveryPattern?.let { key ->
            PanelTestPattern.fromStorageKey(key)?.let { patternLabel(context, it) }
        }
        val lines = buildList {
            add("$time · $outcome")
            add(
                context.getString(
                    R.string.troubleshoot_entry_progress,
                    entry.maxProgress,
                    entry.durationMs / 1000L,
                ),
            )
            if (!entry.driverName.isBlank()) {
                add(context.getString(R.string.troubleshoot_entry_driver, entry.driverName))
            }
            if (pattern != null) {
                add(context.getString(R.string.troubleshoot_entry_pattern, pattern))
            }
            if (entry.refreshStalled) {
                add(context.getString(R.string.troubleshoot_entry_refresh_stalled))
            }
            if (entry.needsPanelVerify) {
                add(context.getString(R.string.troubleshoot_entry_needs_verify))
            }
            if (entry.message.isNotBlank()) {
                add(entry.message)
            }
        }
        return lines.joinToString("\n")
    }

    fun buildExportReport(context: Context, preferences: Preferences): String {
        val history = loadHistory(context)
        val panel = preferences.getScreenSize()
        val colorMode = preferences.getColorMode().name
        val sb = StringBuilder()
        sb.appendLine("Sankara display troubleshooting report")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Panel: $panel")
        sb.appendLine("Color mode: $colorMode")
        sb.appendLine("NFC password set: ${preferences.getDevicePassword().isNotEmpty()}")
        sb.appendLine()
        if (history.isEmpty()) {
            sb.appendLine("No sync history recorded yet.")
        } else {
            sb.appendLine("Recent sync attempts (${history.size}):")
            history.forEachIndexed { index, entry ->
                sb.appendLine()
                sb.appendLine("--- #${index + 1} ---")
                sb.appendLine(formatEntry(context, entry))
            }
        }
        return sb.toString().trim()
    }

    fun patternLabel(context: Context, pattern: PanelTestPattern): String = when (pattern) {
        PanelTestPattern.WHITE -> context.getString(R.string.troubleshoot_pattern_white)
        PanelTestPattern.BLACK -> context.getString(R.string.troubleshoot_pattern_black)
        PanelTestPattern.CHECKERBOARD -> context.getString(R.string.troubleshoot_pattern_checkerboard)
        PanelTestPattern.HORIZONTAL_BARS -> context.getString(R.string.troubleshoot_pattern_bars)
    }

    private fun loadHistory(raw: String): List<Entry> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        Entry(
                            timestampMs = obj.getLong("timestampMs"),
                            success = obj.getBoolean("success"),
                            message = obj.optString("message", ""),
                            driverName = obj.optString("driverName", ""),
                            maxProgress = obj.optInt("maxProgress", -1),
                            durationMs = obj.optLong("durationMs", 0L),
                            recoveryPattern = if (obj.has("recoveryPattern") && !obj.isNull("recoveryPattern")) {
                                obj.getString("recoveryPattern")
                            } else {
                                null
                            },
                            refreshStalled = obj.optBoolean("refreshStalled", false),
                            needsPanelVerify = obj.optBoolean("needsPanelVerify", false),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeHistory(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("timestampMs", entry.timestampMs)
                    put("success", entry.success)
                    put("message", entry.message)
                    put("driverName", entry.driverName)
                    put("maxProgress", entry.maxProgress)
                    put("durationMs", entry.durationMs)
                    put("recoveryPattern", entry.recoveryPattern)
                    put("refreshStalled", entry.refreshStalled)
                    put("needsPanelVerify", entry.needsPanelVerify)
                },
            )
        }
        return array.toString()
    }
}
