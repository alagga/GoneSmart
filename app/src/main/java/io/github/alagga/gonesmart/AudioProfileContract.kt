package io.github.alagga.gonesmart

import java.security.MessageDigest
import java.util.Locale

enum class AudioProfileSection {
    EQUALIZER,
    EFFECTS
}

enum class AudioProfileValueType {
    BOOLEAN,
    NUMBER
}

data class AudioProfileSetting(
    val id: String,
    val section: AudioProfileSection,
    val title: String,
    val subtitle: String,
    val valueType: AudioProfileValueType,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val suggestedDefault: String
) {
    fun normalize(raw: String): String? = when (valueType) {
        AudioProfileValueType.BOOLEAN -> when (raw.trim().lowercase(Locale.US)) {
            "true", "1", "on" -> "true"
            "false", "0", "off" -> "false"
            else -> null
        }

        AudioProfileValueType.NUMBER -> {
            val parsed = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
            val bounded = parsed
                .let { value -> minimum?.let { value.coerceAtLeast(it) } ?: value }
                .let { value -> maximum?.let { value.coerceAtMost(it) } ?: value }
            val stepped = step
                ?.takeIf { it > 0.0 }
                ?.let { increment ->
                    val origin = minimum ?: 0.0
                    kotlin.math.round((bounded - origin) / increment) * increment + origin
                }
                ?: bounded
            formatNumber(stepped)
        }
    }

    companion object {
        fun formatNumber(value: Double): String {
            if (!value.isFinite()) return "0"
            val rounded = kotlin.math.round(value * 1000.0) / 1000.0
            return if (kotlin.math.abs(rounded - kotlin.math.round(rounded)) < 0.000001) {
                kotlin.math.round(rounded).toLong().toString()
            } else {
                String.format(Locale.US, "%.3f", rounded).trimEnd('0').trimEnd('.')
            }
        }
    }
}

data class AudioRouteProfile(
    val id: String,
    val label: String,
    val bluetooth: Boolean,
    val lastSeenEpochMs: Long,
    val active: Boolean = false
)

object GoneSmartAudioProfiles {
    const val GROUP = "audio_profiles"
    const val LOCAL_ROUTE_ID = "local"
    const val LOCAL_ROUTE_LABEL = "No Bluetooth device"

    const val KEY_ACTIVE_ROUTE = "route.active"
    const val KEY_SCHEMA_VERSION = "schema.version"
    const val SCHEMA_VERSION = 1

    private const val ROUTE_PREFIX = "route."
    private const val OVERRIDE_PREFIX = "override."
    private const val NATIVE_PREFIX = "native."

    val equalizerSettings: List<AudioProfileSetting> = buildList {
        add(
            AudioProfileSetting(
                id = "eq.enabled",
                section = AudioProfileSection.EQUALIZER,
                title = "Equalizer",
                subtitle = "Override GMMP's equalizer on/off state.",
                valueType = AudioProfileValueType.BOOLEAN,
                suggestedDefault = "true"
            )
        )
        add(
            AudioProfileSetting(
                id = "eq.preamp_db",
                section = AudioProfileSection.EQUALIZER,
                title = "Preamp",
                subtitle = "Gain applied before the equalizer bands.",
                valueType = AudioProfileValueType.NUMBER,
                unit = "dB",
                minimum = -12.0,
                maximum = 12.0,
                step = 0.1,
                suggestedDefault = "0"
            )
        )
        listOf(
            32 to "32 Hz",
            64 to "64 Hz",
            128 to "128 Hz",
            256 to "256 Hz",
            512 to "512 Hz",
            1000 to "1 kHz",
            2000 to "2 kHz",
            4000 to "4 kHz",
            8000 to "8 kHz",
            16000 to "16 kHz"
        ).forEach { (frequency, label) ->
            add(
                AudioProfileSetting(
                    id = "eq.band_${frequency}_db",
                    section = AudioProfileSection.EQUALIZER,
                    title = label,
                    subtitle = "Override only this equalizer band.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "dB",
                    minimum = -12.0,
                    maximum = 12.0,
                    step = 0.1,
                    suggestedDefault = "0"
                )
            )
        }
    }

    val effectSettings: List<AudioProfileSetting> = listOf(
        AudioProfileSetting(
            id = "balance.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Balance",
            subtitle = "Override whether GMMP's balance adjustment is active.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "balance.percent",
            section = AudioProfileSection.EFFECTS,
            title = "Balance amount",
            subtitle = "Left/right balance. 0 keeps both channels equal.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "%",
            minimum = -100.0,
            maximum = 100.0,
            step = 1.0,
            suggestedDefault = "0"
        ),
        AudioProfileSetting(
            id = "mono.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Force mono",
            subtitle = "Override GMMP's mono downmix setting.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "limiter.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Limiter",
            subtitle = "Override whether GMMP's limiter is enabled.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "true"
        ),
        AudioProfileSetting(
            id = "limiter.attack_ms",
            section = AudioProfileSection.EFFECTS,
            title = "Limiter attack",
            subtitle = "Limiter attack time. The native GMMP value stays untouched when inherited.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "ms",
            minimum = 0.0,
            step = 1.0,
            suggestedDefault = "0"
        ),
        AudioProfileSetting(
            id = "limiter.release_ms",
            section = AudioProfileSection.EFFECTS,
            title = "Limiter release",
            subtitle = "Limiter release time. The native GMMP value stays untouched when inherited.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "ms",
            minimum = 0.0,
            step = 1.0,
            suggestedDefault = "500"
        ),
        AudioProfileSetting(
            id = "bass.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Bass boost",
            subtitle = "Override whether GMMP's bass boost is enabled.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "bass.strength_percent",
            section = AudioProfileSection.EFFECTS,
            title = "Bass boost strength",
            subtitle = "Override only the bass boost strength.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "%",
            minimum = 0.0,
            maximum = 100.0,
            step = 1.0,
            suggestedDefault = "100"
        ),
        AudioProfileSetting(
            id = "tempo.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Tempo",
            subtitle = "Override whether GMMP's tempo adjustment is active.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "tempo.percent",
            section = AudioProfileSection.EFFECTS,
            title = "Tempo amount",
            subtitle = "Playback tempo relative to normal speed.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "%",
            minimum = 50.0,
            maximum = 200.0,
            step = 1.0,
            suggestedDefault = "100"
        ),
        AudioProfileSetting(
            id = "pitch_correction.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Auto pitch correction",
            subtitle = "Override GMMP's automatic pitch correction setting.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "true"
        ),
        AudioProfileSetting(
            id = "virtualizer.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Virtualizer",
            subtitle = "Override whether GMMP's virtualizer is enabled.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "virtualizer.strength_percent",
            section = AudioProfileSection.EFFECTS,
            title = "Virtualizer strength",
            subtitle = "Override only the virtualizer strength.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "%",
            minimum = 0.0,
            maximum = 100.0,
            step = 1.0,
            suggestedDefault = "0"
        ),
        AudioProfileSetting(
            id = "amplifier.enabled",
            section = AudioProfileSection.EFFECTS,
            title = "Amplifier",
            subtitle = "Override whether GMMP's amplifier is enabled.",
            valueType = AudioProfileValueType.BOOLEAN,
            suggestedDefault = "false"
        ),
        AudioProfileSetting(
            id = "amplifier.gain_db",
            section = AudioProfileSection.EFFECTS,
            title = "Amplifier gain",
            subtitle = "Override only GMMP's amplifier gain.",
            valueType = AudioProfileValueType.NUMBER,
            unit = "dB",
            step = 0.1,
            suggestedDefault = "0"
        )
    )

    val allSettings: List<AudioProfileSetting> = equalizerSettings + effectSettings

    fun setting(id: String): AudioProfileSetting? = allSettings.firstOrNull { it.id == id }

    fun overrideKey(routeId: String, settingId: String): String =
        "$OVERRIDE_PREFIX$routeId.$settingId"

    fun nativeValueKey(settingId: String): String = "$NATIVE_PREFIX$settingId"

    fun routeLabelKey(routeId: String): String = "$ROUTE_PREFIX$routeId.label"
    fun routeBluetoothKey(routeId: String): String = "$ROUTE_PREFIX$routeId.bluetooth"
    fun routeLastSeenKey(routeId: String): String = "$ROUTE_PREFIX$routeId.last_seen"

    fun isOverrideKey(key: String): Boolean = key.startsWith(OVERRIDE_PREFIX)

    fun routeIdsFromKeys(keys: Set<String>): Set<String> = keys.mapNotNull { key ->
        if (!key.startsWith(ROUTE_PREFIX) || key == KEY_ACTIVE_ROUTE) return@mapNotNull null
        val remainder = key.removePrefix(ROUTE_PREFIX)
        val suffixIndex = remainder.indexOf('.')
        if (suffixIndex <= 0) null else remainder.substring(0, suffixIndex)
    }.toSet()

    fun bluetoothRouteId(type: Int, address: String?, label: String?): String {
        val stableAddress = address?.trim().orEmpty()
        val stableLabel = label?.trim().orEmpty()
        val identity = "$type|${stableAddress.ifBlank { stableLabel.lowercase(Locale.US) }}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "bt_" + digest.take(10).joinToString("") { byte -> "%02x".format(byte) }
    }
}
