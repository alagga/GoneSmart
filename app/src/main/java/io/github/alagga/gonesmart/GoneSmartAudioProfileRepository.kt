package io.github.alagga.gonesmart

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class GoneSmartAudioProfileRepository(context: Context) {

    companion object {
        private const val TAG = "GoneSmartAudioProfiles"
        private const val LOCAL_PREFS = "gonesmart_audio_profiles_local_v2"
        private const val KEY_PENDING = "local.pending_keys"
        private const val STANDARD_PREFIX = "standard."
        private const val NATIVE_PRESET_PREFIX = "native_preset."
    }

    private val appContext = context.applicationContext
    private val local = appContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    init {
        ensureLocalRoute()
    }

    fun synchronizeWithFramework() {
        val service = GoneSmartApplication.xposedService ?: return
        val remote = runCatching {
            service.getRemotePreferences(GoneSmartAudioProfiles.GROUP)
        }.getOrElse {
            Log.w(TAG, "Audio-profile framework preferences unavailable", it)
            return
        }

        flushPending(remote)

        val pending = pendingKeys()
        val editor = local.edit()
        remote.all.forEach { (key, value) ->
            if (key !in pending) put(editor, key, value)
        }
        editor.apply()
        ensureLocalRoute()
    }

    fun routes(): List<AudioRouteProfile> {
        ensureLocalRoute()
        val all = local.all
        val activeId = local.getString(
            GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE,
            GoneSmartAudioProfiles.LOCAL_ROUTE_ID
        ) ?: GoneSmartAudioProfiles.LOCAL_ROUTE_ID

        val ids = GoneSmartAudioProfiles.routeIdsFromKeys(all.keys) + GoneSmartAudioProfiles.LOCAL_ROUTE_ID
        return ids.map { id ->
            val isLocal = AudioProfileRuntimePolicy.isStandardRoute(id)
            AudioRouteProfile(
                id = id,
                label = if (isLocal) {
                    AudioProfileRuntimePolicy.STANDARD_LABEL
                } else {
                    local.getString(GoneSmartAudioProfiles.routeLabelKey(id), null)
                        ?.takeIf { it.isNotBlank() }
                        ?: "Bluetooth device"
                },
                bluetooth = !isLocal && local.getBoolean(GoneSmartAudioProfiles.routeBluetoothKey(id), true),
                lastSeenEpochMs = local.getLong(GoneSmartAudioProfiles.routeLastSeenKey(id), 0L),
                active = id == activeId
            )
        }.sortedWith(
            compareByDescending<AudioRouteProfile> { it.active }
                .thenByDescending { it.lastSeenEpochMs }
                .thenBy { it.label.lowercase() }
        )
    }

    fun activeRouteId(): String =
        local.getString(GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE, GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
            ?: GoneSmartAudioProfiles.LOCAL_ROUTE_ID

    fun recordObservedRoute(route: AudioRouteProfile) {
        val id = if (route.bluetooth) {
            GoneSmartAudioProfiles.bluetoothRouteId(
                type = 0,
                address = null,
                label = route.label
            )
        } else {
            GoneSmartAudioProfiles.LOCAL_ROUTE_ID
        }
        val keys = mutableSetOf(
            GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE,
            GoneSmartAudioProfiles.KEY_SCHEMA_VERSION
        )
        val editor = local.edit()
            .putString(GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE, id)
            .putInt(GoneSmartAudioProfiles.KEY_SCHEMA_VERSION, GoneSmartAudioProfiles.SCHEMA_VERSION)

        val label = if (AudioProfileRuntimePolicy.isStandardRoute(id)) {
            AudioProfileRuntimePolicy.STANDARD_LABEL
        } else {
            route.label.ifBlank { "Bluetooth device" }
        }
        editor
            .putString(GoneSmartAudioProfiles.routeLabelKey(id), label)
            .putBoolean(GoneSmartAudioProfiles.routeBluetoothKey(id), !AudioProfileRuntimePolicy.isStandardRoute(id))
            .putLong(GoneSmartAudioProfiles.routeLastSeenKey(id), route.lastSeenEpochMs)
        keys += GoneSmartAudioProfiles.routeLabelKey(id)
        keys += GoneSmartAudioProfiles.routeBluetoothKey(id)
        keys += GoneSmartAudioProfiles.routeLastSeenKey(id)

        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    fun isGroupOverridden(routeId: String, groupId: String): Boolean {
        if (AudioProfileRuntimePolicy.isStandardRoute(routeId)) return false
        return local.getBoolean(GoneSmartAudioProfiles.overrideKey(routeId, groupId), false)
    }

    /**
     * Bluetooth profiles are overlays on Standard. Standard itself never has
     * an override marker: it is the saved native baseline.
     */
    fun setGroupOverride(routeId: String, groupId: String, enabled: Boolean): Boolean {
        if (AudioProfileRuntimePolicy.isStandardRoute(routeId)) return false
        val group = GoneSmartAudioProfiles.group(groupId) ?: return false
        val overrideKey = GoneSmartAudioProfiles.overrideKey(routeId, groupId)
        val keys = mutableSetOf(overrideKey)
        val editor = local.edit()

        if (enabled) {
            val missing = group.fields.filter { field ->
                val valueKey = GoneSmartAudioProfiles.valueKey(routeId, field.id)
                !local.contains(valueKey) && inheritedValue(routeId, field.id) == null
            }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Override deferred; Standard values missing for ${missing.joinToString { it.id }}")
                return false
            }

            editor.putBoolean(overrideKey, true)
            group.fields.forEach { field ->
                val valueKey = GoneSmartAudioProfiles.valueKey(routeId, field.id)
                if (!local.contains(valueKey)) {
                    val seed = inheritedValue(routeId, field.id) ?: return false
                    editor.putString(valueKey, seed)
                    keys += valueKey
                }
            }
        } else {
            // Keep dormant values so temporarily returning this card to
            // Standard does not destroy the user's saved Bluetooth tuning.
            editor.remove(overrideKey)
        }

        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
        return true
    }

    fun value(routeId: String, fieldId: String): String? =
        local.getString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), null)

    fun setValue(routeId: String, fieldId: String, rawValue: String) {
        if (AudioProfileRuntimePolicy.isStandardRoute(routeId)) return
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return
        val normalized = field.normalize(rawValue) ?: return
        writeString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), normalized)
    }

    /** Last passively observed live GMMP state, which may be temporary on Bluetooth. */
    fun nativeValue(fieldId: String): String? =
        local.getString(GoneSmartAudioProfiles.nativeValueKey(fieldId), null)

    /** Saved normal GMMP state used as the base for every Bluetooth profile. */
    fun standardValue(fieldId: String): String? =
        local.getString(standardValueKey(fieldId), null)

    fun isStandardReady(): Boolean = GoneSmartAudioProfiles.allFields.all { field ->
        standardValue(field.id)?.let(field::normalize) != null
    }

    fun inheritedValue(routeId: String, fieldId: String): String? = standardValue(fieldId)

    fun inheritedObservedCount(routeId: String, group: AudioProfileGroup): Int =
        group.fields.count { inheritedValue(routeId, it.id) != null }

    /**
     * Every observation updates the live mirror. Only observations made while
     * no Bluetooth route is active are allowed to redefine Standard.
     */
    fun setNativeValue(fieldId: String, rawValue: String, observedRouteId: String = activeRouteId()) {
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return
        val normalized = field.normalize(rawValue) ?: return
        val keys = mutableSetOf(GoneSmartAudioProfiles.nativeValueKey(fieldId))
        val editor = local.edit().putString(GoneSmartAudioProfiles.nativeValueKey(fieldId), normalized)
        if (AudioProfileRuntimePolicy.shouldUpdateStandardFromNativeObservation(observedRouteId)) {
            editor.putString(standardValueKey(fieldId), normalized)
            keys += standardValueKey(fieldId)
        }
        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    fun clearNativeValue(fieldId: String, observedRouteId: String = activeRouteId()) {
        GoneSmartAudioProfiles.field(fieldId) ?: return
        val keys = mutableSetOf(GoneSmartAudioProfiles.nativeValueKey(fieldId))
        val editor = local.edit().remove(GoneSmartAudioProfiles.nativeValueKey(fieldId))
        if (AudioProfileRuntimePolicy.shouldUpdateStandardFromNativeObservation(observedRouteId)) {
            editor.remove(standardValueKey(fieldId))
            keys += standardValueKey(fieldId)
        }
        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    /**
     * Persist a complete passively observed GMMP preset curve. This data is
     * reference data, not a route-specific override and therefore survives
     * output switches. Any Bluetooth profile already pointing at this named
     * preset is backfilled with the newly learned native curve.
     */
    fun setNativePresetShape(presetName: String, rawShape: Map<String, String>) {
        if (presetName.isBlank() || presetName == GoneSmartAudioProfiles.CUSTOM_PRESET) return
        if (rawShape.keys.toSet() != GoneSmartAudioProfiles.equalizerShapeFieldIds.toSet()) return
        val normalized = GoneSmartAudioProfiles.equalizerShapeFieldIds.associateWith { fieldId ->
            val field = GoneSmartAudioProfiles.field(fieldId) ?: return
            field.normalize(rawShape[fieldId] ?: return) ?: return
        }
        val keys = mutableSetOf<String>()
        val editor = local.edit()
        val nameKey = GoneSmartAudioProfiles.nativePresetNameKey(presetName)
        editor.putString(nameKey, presetName)
        keys += nameKey
        normalized.forEach { (fieldId, value) ->
            val key = GoneSmartAudioProfiles.nativePresetValueKey(presetName, fieldId)
            editor.putString(key, value)
            keys += key
        }

        routes()
            .asSequence()
            .filterNot { AudioProfileRuntimePolicy.isStandardRoute(it.id) }
            .filter { route -> value(route.id, "eq.preset") == presetName }
            .forEach { route ->
                normalized.forEach { (fieldId, value) ->
                    val key = GoneSmartAudioProfiles.valueKey(route.id, fieldId)
                    editor.putString(key, value)
                    keys += key
                }
            }

        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    /** Select only a named preset whose exact native PRE/band shape is known. */
    fun applyEqualizerPreset(routeId: String, presetName: String): Boolean {
        if (AudioProfileRuntimePolicy.isStandardRoute(routeId)) return false
        if (presetName.isBlank() || presetName == GoneSmartAudioProfiles.CUSTOM_PRESET) return false
        val normalizedPreset = GoneSmartAudioProfiles.field("eq.preset")?.normalize(presetName) ?: return false
        val shape = nativePresetShape(normalizedPreset) ?: return false
        val keys = mutableSetOf<String>()
        val editor = local.edit()

        val presetKey = GoneSmartAudioProfiles.valueKey(routeId, "eq.preset")
        editor.putString(presetKey, normalizedPreset)
        keys += presetKey

        shape.forEach { (fieldId, value) ->
            val key = GoneSmartAudioProfiles.valueKey(routeId, fieldId)
            editor.putString(key, value)
            keys += key
        }

        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
        return true
    }

    fun isNativePresetAvailable(presetName: String): Boolean = nativePresetShape(presetName) != null

    fun availableNativePresets(): List<String> {
        val learnedNames = local.all
            .asSequence()
            .filter { (key, value) ->
                key.startsWith(NATIVE_PRESET_PREFIX) && key.endsWith(".name") && value is String
            }
            .mapNotNull { (_, value) -> (value as? String)?.takeIf { it.isNotBlank() } }
            .toSet()
        val ordered = linkedSetOf<String>()
        GoneSmartAudioProfiles.presetSuggestions
            .filter(::isNativePresetAvailable)
            .forEach(ordered::add)
        learnedNames
            .filter(::isNativePresetAvailable)
            .sortedBy { it.lowercase() }
            .forEach(ordered::add)
        standardValue("eq.preset")
            ?.takeIf(::isNativePresetAvailable)
            ?.let(ordered::add)
        return ordered.toList()
    }

    fun clearRouteOverrides(routeId: String) {
        val overridePrefix = GoneSmartAudioProfiles.overrideKey(routeId, "")
        val valuePrefix = GoneSmartAudioProfiles.valueKey(routeId, "")
        val keys = local.all.keys.filter {
            it.startsWith(overridePrefix) || it.startsWith(valuePrefix)
        }.toSet()
        if (keys.isEmpty()) return
        val editor = local.edit()
        keys.forEach(editor::remove)
        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    fun overrideCount(routeId: String): Int {
        if (AudioProfileRuntimePolicy.isStandardRoute(routeId)) return 0
        val prefix = GoneSmartAudioProfiles.overrideKey(routeId, "")
        return local.all.count { (key, value) -> key.startsWith(prefix) && value == true }
    }

    private fun nativePresetShape(presetName: String): Map<String, String>? {
        val stored = GoneSmartAudioProfiles.equalizerShapeFieldIds.mapNotNull { fieldId ->
            local.getString(GoneSmartAudioProfiles.nativePresetValueKey(presetName, fieldId), null)
                ?.let { fieldId to it }
        }.toMap()
        if (stored.size == GoneSmartAudioProfiles.equalizerShapeFieldIds.size) return stored

        // If Standard currently has this named preset, its complete saved shape
        // is also a valid native reference without relying on a Bluetooth live state.
        if (standardValue("eq.preset") == presetName) {
            val standard = GoneSmartAudioProfiles.equalizerShapeFieldIds.mapNotNull { fieldId ->
                standardValue(fieldId)?.let { fieldId to it }
            }.toMap()
            if (standard.size == GoneSmartAudioProfiles.equalizerShapeFieldIds.size) return standard
        }
        return null
    }

    private fun ensureLocalRoute() {
        val labelKey = GoneSmartAudioProfiles.routeLabelKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
        local.edit()
            .putString(labelKey, AudioProfileRuntimePolicy.STANDARD_LABEL)
            .putBoolean(GoneSmartAudioProfiles.routeBluetoothKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID), false)
            .putLong(
                GoneSmartAudioProfiles.routeLastSeenKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID),
                local.getLong(GoneSmartAudioProfiles.routeLastSeenKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID), 0L)
            )
            .apply()
        if (!local.contains(GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE)) {
            local.edit()
                .putString(GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE, GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
                .putInt(GoneSmartAudioProfiles.KEY_SCHEMA_VERSION, GoneSmartAudioProfiles.SCHEMA_VERSION)
                .apply()
        }
    }

    private fun standardValueKey(fieldId: String): String = "$STANDARD_PREFIX$fieldId"

    private fun writeString(key: String, value: String) {
        local.edit().putString(key, value).apply()
        markPending(setOf(key))
        synchronizeWithFramework()
    }

    private fun pendingKeys(): Set<String> = local.getStringSet(KEY_PENDING, emptySet())?.toSet().orEmpty()

    private fun markPending(keys: Set<String>) {
        if (keys.isEmpty()) return
        local.edit().putStringSet(KEY_PENDING, pendingKeys() + keys).apply()
    }

    private fun flushPending(remote: SharedPreferences) {
        val pending = pendingKeys()
        if (pending.isEmpty()) return
        val editor = remote.edit()
        pending.forEach { key ->
            if (local.contains(key)) {
                put(editor, key, local.all[key])
            } else {
                editor.remove(key)
            }
        }
        val saved = runCatching { editor.commit() }.getOrDefault(false)
        if (saved) {
            local.edit().putStringSet(KEY_PENDING, emptySet()).apply()
        } else {
            Log.w(TAG, "Audio-profile framework write deferred")
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> editor.putString(key, value.toString())
        }
    }
}
