package io.github.alagga.gonesmart

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GoneSmartEventStore {
    private const val PREFS = "gonesmart_runtime"
    private const val KEY_LOG = "log"
    private const val KEY_MODE = "mode"
    private const val KEY_MESSAGE = "message"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val MAX_LINES = 400

    data class RuntimeSnapshot(
        val mode: String,
        val message: String,
        val timestamp: Long
    )

    @Synchronized
    fun record(
        context: Context,
        mode: String,
        message: String,
        appendEvent: Boolean,
        eventOnly: Boolean = false,
        category: String? = null
    ) {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        // UI actions and ordinary playlist operations must never replace
        // the Home tab's Auto-DJ readiness/fallback status.
        val editor = prefs.edit()
        if (!eventOnly) {
            editor.putString(KEY_MODE, mode)
                .putString(KEY_MESSAGE, message)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }

        if (appendEvent) {
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val section = category ?: when (mode) {
                GoneSmartRuntimeContract.MODE_SMART,
                GoneSmartRuntimeContract.MODE_FALLBACK,
                GoneSmartRuntimeContract.MODE_STOPPED ->
                    GoneSmartRuntimeContract.CATEGORY_SMART_DJ
                else -> GoneSmartRuntimeContract.CATEGORY_SYSTEM
            }
            val line = "${formatter.format(Date())}  [$section] $message"
            val existing = prefs.getString(KEY_LOG, "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toMutableList()

            existing += line
            val trimmed = existing.takeLast(MAX_LINES)
            editor.putString(KEY_LOG, trimmed.joinToString("\n"))
        }

        editor.apply()
    }

    fun snapshot(context: Context): RuntimeSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
        return RuntimeSnapshot(
            mode = prefs.getString(KEY_MODE, GoneSmartRuntimeContract.MODE_NONE)
                ?: GoneSmartRuntimeContract.MODE_NONE,
            message = prefs.getString(KEY_MESSAGE, "No runtime activity yet.")
                ?: "No runtime activity yet.",
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        )
    }

    fun logText(context: Context): String {
        return context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).getString(KEY_LOG, "").orEmpty()
    }

    data class EventSummary(
        val total: Int,
        val smartDj: Int,
        val playlists: Int,
        val flip: Int,
        val other: Int
    )

    fun summary(context: Context): EventSummary {
        val lines = logText(context).lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val smart = lines.count { it.contains("[Smart DJ]") }
        val playlists = lines.count { it.contains("[Playlists]") }
        val flip = lines.count { it.contains("[Flip]") }
        return EventSummary(
            total = lines.size,
            smartDj = smart,
            playlists = playlists,
            flip = flip,
            other = lines.size - smart - playlists - flip
        )
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit()
            .remove(KEY_LOG)
            .apply()
    }
}
