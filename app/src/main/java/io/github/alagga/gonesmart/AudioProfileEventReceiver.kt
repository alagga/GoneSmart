package io.github.alagga.gonesmart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AudioProfileEventReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ROUTE_OBSERVED = "io.github.alagga.gonesmart.action.AUDIO_ROUTE_OBSERVED"
        const val ACTION_NATIVE_VALUE_OBSERVED = "io.github.alagga.gonesmart.action.AUDIO_NATIVE_VALUE_OBSERVED"

        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_ROUTE_LABEL = "route_label"
        const val EXTRA_ROUTE_BLUETOOTH = "route_bluetooth"
        const val EXTRA_LAST_SEEN = "last_seen"
        const val EXTRA_SETTING_ID = "setting_id"
        const val EXTRA_SETTING_VALUE = "setting_value"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val repository = GoneSmartAudioProfileRepository(context)
        when (intent.action) {
            ACTION_ROUTE_OBSERVED -> {
                val id = intent.getStringExtra(EXTRA_ROUTE_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: GoneSmartAudioProfiles.LOCAL_ROUTE_ID
                val bluetooth = intent.getBooleanExtra(EXTRA_ROUTE_BLUETOOTH, id != GoneSmartAudioProfiles.LOCAL_ROUTE_ID)
                val label = intent.getStringExtra(EXTRA_ROUTE_LABEL)
                    ?.takeIf { it.isNotBlank() }
                    ?: if (bluetooth) "Bluetooth device" else GoneSmartAudioProfiles.LOCAL_ROUTE_LABEL
                repository.recordObservedRoute(
                    AudioRouteProfile(
                        id = id,
                        label = label,
                        bluetooth = bluetooth,
                        lastSeenEpochMs = intent.getLongExtra(EXTRA_LAST_SEEN, System.currentTimeMillis()),
                        active = true
                    )
                )
            }

            ACTION_NATIVE_VALUE_OBSERVED -> {
                val settingId = intent.getStringExtra(EXTRA_SETTING_ID) ?: return
                val value = intent.getStringExtra(EXTRA_SETTING_VALUE) ?: return
                repository.setNativeValue(settingId, value)
            }
        }
    }
}
