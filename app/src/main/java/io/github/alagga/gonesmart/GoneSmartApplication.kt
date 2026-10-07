package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class GoneSmartApplication : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private const val PREFERENCES = "gonesmart_ui_settings"
        private const val PENDING_MIX_ENABLE = "pending_track_mix_enable"
        private const val TAG = "GoneSmart"
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    /**
     * Only an explicit Track Mix action asks the companion to permanently
     * turn on Smart DJ. The hooked GMMP process cannot mutate its own
     * libxposed RemotePreferences reliably, so persist the local request
     * here and flush it once the framework's writable service is ready.
     */
    fun enableSmartDjFromMix() {
        val prefs = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(GoneSmartSettingsKeys.KEY_ENABLED, true)
            .putBoolean(PENDING_MIX_ENABLE, true)
            .apply()
        applyPendingTrackMixEnable()
    }

    private fun applyPendingTrackMixEnable() {
        val prefs = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PENDING_MIX_ENABLE, false)) return
        val service = xposedService ?: return
        runCatching {
            service.getRemotePreferences(GoneSmartSettingsKeys.GROUP)
                .edit()
                .putBoolean(GoneSmartSettingsKeys.KEY_INITIALIZED, true)
                .putBoolean(GoneSmartSettingsKeys.KEY_ENABLED, true)
                .commit()
        }.onSuccess { saved ->
            if (saved) {
                prefs.edit().remove(PENDING_MIX_ENABLE).apply()
                Log.i(TAG, "TRACK MIX | Smart DJ preference enabled")
            } else {
                Log.w(TAG, "TRACK MIX | framework preference write deferred")
            }
        }.onFailure {
            Log.w(TAG, "TRACK MIX | waiting to persist Smart DJ preference", it)
        }
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        applyPendingTrackMixEnable()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
        }
    }
}
