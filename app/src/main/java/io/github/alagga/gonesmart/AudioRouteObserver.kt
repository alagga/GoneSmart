package io.github.alagga.gonesmart

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class AudioRouteObserver(
    context: Context,
    private val onRouteChanged: (AudioRouteProfile) -> Unit
) {

    companion object {
        private const val TAG = "GoneSmartAudioProfiles"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastRouteId: String? = null
    private var mostRecentlyAddedBluetoothId: String? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices
                .filter { it.isSink && isBluetoothType(it.type) }
                .lastOrNull()
                ?.let { mostRecentlyAddedBluetoothId = routeId(it) }
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { routeId(it) == mostRecentlyAddedBluetoothId }) {
                mostRecentlyAddedBluetoothId = null
            }
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            audioManager.registerAudioDeviceCallback(callback, mainHandler)
        }.onFailure {
            Log.w(TAG, "Could not register audio-device callback", it)
        }
        refresh(force = true)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
            .onFailure { Log.w(TAG, "Could not unregister audio-device callback", it) }
    }

    private fun refresh(force: Boolean = false) {
        val bluetoothOutputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && isBluetoothType(it.type) }
        }.getOrElse {
            Log.w(TAG, "Could not inspect audio outputs", it)
            emptyList()
        }

        val selected = bluetoothOutputs.firstOrNull { routeId(it) == mostRecentlyAddedBluetoothId }
            ?: bluetoothOutputs.lastOrNull()
        val route = if (selected == null) {
            AudioRouteProfile(
                id = GoneSmartAudioProfiles.LOCAL_ROUTE_ID,
                label = AudioProfileRuntimePolicy.STANDARD_LABEL,
                bluetooth = false,
                lastSeenEpochMs = System.currentTimeMillis(),
                active = true
            )
        } else {
            val label = selected.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth device"
            AudioRouteProfile(
                id = GoneSmartAudioProfiles.bluetoothRouteId(
                    type = selected.type,
                    address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runCatching { selected.address }.getOrNull()
                    } else {
                        null
                    },
                    label = label
                ),
                label = label,
                bluetooth = true,
                lastSeenEpochMs = System.currentTimeMillis(),
                active = true
            )
        }

        if (!force && route.id == lastRouteId) return
        lastRouteId = route.id
        Log.i(
            TAG,
            "AUDIO ROUTE | id=${route.id} | label=${route.label} | bluetooth=${route.bluetooth}"
        )
        onRouteChanged(route)
    }

    private fun routeId(device: AudioDeviceInfo): String = GoneSmartAudioProfiles.bluetoothRouteId(
        type = device.type,
        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { device.address }.getOrNull()
        } else {
            null
        },
        label = device.productName?.toString()
    )

    private fun isBluetoothType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
        else -> false
    }
}
