package io.github.alagga.gonesmart

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Synchronous cross-process bridge for native GMMP audio observations.
 *
 * The GMMP process cannot write GoneSmart RemotePreferences. A provider call
 * gives the observer a positive local-persistence acknowledgement instead of
 * assuming that a background broadcast was delivered. The existing explicit
 * broadcast receiver remains the fallback transport.
 */
object AudioProfileCompanionBridge {
    const val AUTHORITY = "io.github.alagga.gonesmart.audio-profile-bridge"
    val URI: Uri = Uri.parse("content://$AUTHORITY")

    const val METHOD_ROUTE = "route_observed"
    const val METHOD_NATIVE_VALUE = "native_value_observed"
    const val METHOD_NATIVE_PRESET = "native_preset_observed"
    const val EXTRA_OK = "ok"

    fun report(context: Context, method: String, extras: Bundle): Boolean =
        runCatching {
            context.contentResolver.call(URI, method, null, extras)
                ?.getBoolean(EXTRA_OK, false) == true
        }.getOrDefault(false)
}

class AudioProfileBridgeProvider : ContentProvider() {

    companion object {
        private const val GMMP_PACKAGE = "gonemad.gmmp"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val appContext = context?.applicationContext ?: return result(false)
        if (!trustedCaller(appContext)) return result(false)
        val payload = extras ?: return result(false)
        val repository = GoneSmartAudioProfileRepository(appContext)

        return when (method) {
            AudioProfileCompanionBridge.METHOD_ROUTE -> {
                val id = payload.getString(AudioProfileEventReceiver.EXTRA_ROUTE_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: GoneSmartAudioProfiles.LOCAL_ROUTE_ID
                val bluetooth = payload.getBoolean(
                    AudioProfileEventReceiver.EXTRA_ROUTE_BLUETOOTH,
                    id != GoneSmartAudioProfiles.LOCAL_ROUTE_ID
                )
                val label = payload.getString(AudioProfileEventReceiver.EXTRA_ROUTE_LABEL)
                    ?.takeIf { it.isNotBlank() }
                    ?: if (bluetooth) "Bluetooth device" else AudioProfileRuntimePolicy.STANDARD_LABEL
                repository.recordObservedRoute(
                    AudioRouteProfile(
                        id = id,
                        label = label,
                        bluetooth = bluetooth,
                        lastSeenEpochMs = payload.getLong(
                            AudioProfileEventReceiver.EXTRA_LAST_SEEN,
                            System.currentTimeMillis()
                        ),
                        active = true
                    )
                )
                result(true)
            }

            AudioProfileCompanionBridge.METHOD_NATIVE_VALUE -> {
                val settingId = payload.getString(AudioProfileEventReceiver.EXTRA_SETTING_ID)
                    ?: return result(false)
                val field = GoneSmartAudioProfiles.field(settingId) ?: return result(false)
                val routeId = payload.getString(AudioProfileEventReceiver.EXTRA_ROUTE_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: repository.activeRouteId()
                val clear = payload.getBoolean(AudioProfileEventReceiver.EXTRA_SETTING_CLEAR, false)
                if (clear) {
                    repository.clearNativeValue(settingId, routeId)
                    result(repository.nativeValue(settingId) == null)
                } else {
                    val raw = payload.getString(AudioProfileEventReceiver.EXTRA_SETTING_VALUE)
                        ?: return result(false)
                    val normalized = field.normalize(raw) ?: return result(false)
                    repository.setNativeValue(settingId, normalized, routeId)
                    val liveStored = repository.nativeValue(settingId) == normalized
                    val standardStored = !AudioProfileRuntimePolicy.isStandardRoute(routeId) ||
                        repository.standardValue(settingId) == normalized
                    result(liveStored && standardStored)
                }
            }

            AudioProfileCompanionBridge.METHOD_NATIVE_PRESET -> {
                val preset = payload.getString(AudioProfileEventReceiver.EXTRA_PRESET_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: return result(false)
                val ids = payload.getStringArrayList(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_IDS)
                    ?: return result(false)
                val values = payload.getStringArrayList(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_VALUES)
                    ?: return result(false)
                if (ids.size != values.size || ids.isEmpty()) return result(false)
                repository.setNativePresetShape(preset, ids.zip(values).toMap())
                result(repository.isNativePresetAvailable(preset))
            }

            else -> super.call(method, arg, extras) ?: result(false)
        }
    }

    private fun trustedCaller(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        return packages.any { it == GMMP_PACKAGE || it == BuildConfig.APPLICATION_ID }
    }

    private fun result(ok: Boolean): Bundle = Bundle().apply {
        putBoolean(AudioProfileCompanionBridge.EXTRA_OK, ok)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
