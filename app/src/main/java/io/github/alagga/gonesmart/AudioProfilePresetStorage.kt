package io.github.alagga.gonesmart

import android.content.SharedPreferences

/**
 * Versioned validity boundary for learned native Equalizer preset curves.
 *
 * Schema 1 could associate a startup preset label with an unrelated later
 * Equalizer initialization. Those curves are unsafe, so schema 2 deliberately
 * invalidates only learned preset references while preserving route PRE/bands.
 */
object AudioProfilePresetStorage {
    const val SCHEMA_VERSION = 2
    const val SCHEMA_KEY = "native_preset.schema_version"
    const val PREFIX = "native_preset."

    private const val ROUTE_VALUE_PREFIX = "value."
    private const val ROUTE_PRESET_SUFFIX = ".eq.preset"

    fun isCurrent(prefs: SharedPreferences?): Boolean =
        prefs?.getInt(SCHEMA_KEY, 0) == SCHEMA_VERSION

    /** Returns true when the store is current after this call. */
    fun ensureCurrent(prefs: SharedPreferences): Boolean {
        if (isCurrent(prefs)) return true

        val snapshot = prefs.all
        val editor = prefs.edit()
        snapshot.keys
            .filter { it.startsWith(PREFIX) }
            .forEach(editor::remove)

        // Old named-preset labels are no longer trusted, but their explicitly
        // stored route PRE/band values are still useful as a Custom curve.
        snapshot.forEach { (key, value) ->
            if (key.startsWith(ROUTE_VALUE_PREFIX) &&
                key.endsWith(ROUTE_PRESET_SUFFIX) &&
                value is String &&
                value != GoneSmartAudioProfiles.CUSTOM_PRESET
            ) {
                editor.putString(key, GoneSmartAudioProfiles.CUSTOM_PRESET)
            }
        }

        editor.putInt(SCHEMA_KEY, SCHEMA_VERSION)
        return editor.commit()
    }
}
