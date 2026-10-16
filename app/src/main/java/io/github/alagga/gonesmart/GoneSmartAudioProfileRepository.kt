package io.github.alagga.gonesmart

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class GoneSmartAudioProfileRepository(context: Context) {

    companion object {
        private const val TAG = "GoneSmartAudioProfiles"
        private const val LOCAL_PREFS = "gonesmart_audio_profiles_local_v2"
        private const val KEY_PENDING = "local.pending_keys"
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
            val isLocal = id == GoneSmartAudioProfiles.LOCAL_ROUTE_ID
            AudioRouteProfile(
                id = id,
                label = if (isLocal) {
                    GoneSmartAudioProfiles.LOCAL_ROUTE_LABEL
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

        if (id == GoneSmartAudioProfiles.LOCAL_ROUTE_ID) {
            editor
                .putString(GoneSmartAudioProfiles.routeLabelKey(id), GoneSmartAudioProfiles.LOCAL_ROUTE_LABEL)
                .putBoolean(GoneSmartAudioProfiles.routeBluetoothKey(id), false)
                .putLong(GoneSmartAudioProfiles.routeLastSeenKey(id), route.lastSeenEpochMs)
            keys += GoneSmartAudioProfiles.routeLabelKey(id)
            keys += GoneSmartAudioProfiles.routeBluetoothKey(id)
            keys += GoneSmartAudioProfiles.routeLastSeenKey(id)
        } else {
            editor
                .putString(GoneSmartAudioProfiles.routeLabelKey(id), route.label.ifBlank { "Bluetooth device" })
                .putBoolean(GoneSmartAudioProfiles.routeBluetoothKey(id), true)
                .putLong(GoneSmartAudioProfiles.routeLastSeenKey(id), route.lastSeenEpochMs)
            keys += GoneSmartAudioProfiles.routeLabelKey(id)
            keys += GoneSmartAudioProfiles.routeBluetoothKey(id)
            keys += GoneSmartAudioProfiles.routeLastSeenKey(id)
        }
        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    fun isGroupOverridden(routeId: String, groupId: String): Boolean =
        local.getBoolean(GoneSmartAudioProfiles.overrideKey(routeId, groupId), false)

    fun setGroupOverride(routeId: String, groupId: String, enabled: Boolean) {
        val group = GoneSmartAudioProfiles.group(groupId) ?: return
        val overrideKey = GoneSmartAudioProfiles.overrideKey(routeId, groupId)
        val keys = mutableSetOf(overrideKey)
        val editor = local.edit()

        if (enabled) {
            editor.putBoolean(overrideKey, true)
            group.fields.forEach { field ->
                val valueKey = GoneSmartAudioProfiles.valueKey(routeId, field.id)
                if (!local.contains(valueKey)) {
                    val seed = field.normalize(nativeValue(field.id) ?: field.suggestedDefault)
                        ?: field.suggestedDefault
                    editor.putString(valueKey, seed)
                    keys += valueKey
                }
            }
        } else {
            // Keep the dormant field values so temporarily returning to GMMP
            // defaults does not destroy the user's custom profile.
            editor.remove(overrideKey)
        }

        editor.apply()
        markPending(keys)
        synchronizeWithFramework()
    }

    fun value(routeId: String, fieldId: String): String? =
        local.getString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), null)

    fun setValue(routeId: String, fieldId: String, rawValue: String) {
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return
        val normalized = field.normalize(rawValue) ?: return
        writeString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), normalized)
    }

    fun nativeValue(fieldId: String): String? =
        local.getString(GoneSmartAudioProfiles.nativeValueKey(fieldId), null)

    fun setNativeValue(fieldId: String, rawValue: String) {
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return
        val normalized = field.normalize(rawValue) ?: return
        writeString(GoneSmartAudioProfiles.nativeValueKey(fieldId), normalized)
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
        val prefix = GoneSmartAudioProfiles.overrideKey(routeId, "")
        return local.all.count { (key, value) -> key.startsWith(prefix) && value == true }
    }

    private fun ensureLocalRoute() {
        val labelKey = GoneSmartAudioProfiles.routeLabelKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
        if (local.contains(labelKey)) return
        local.edit()
            .putString(labelKey, GoneSmartAudioProfiles.LOCAL_ROUTE_LABEL)
            .putBoolean(GoneSmartAudioProfiles.routeBluetoothKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID), false)
            .putLong(GoneSmartAudioProfiles.routeLastSeenKey(GoneSmartAudioProfiles.LOCAL_ROUTE_ID), 0L)
            .putString(GoneSmartAudioProfiles.KEY_ACTIVE_ROUTE, GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
            .putInt(GoneSmartAudioProfiles.KEY_SCHEMA_VERSION, GoneSmartAudioProfiles.SCHEMA_VERSION)
            .apply()
    }

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
