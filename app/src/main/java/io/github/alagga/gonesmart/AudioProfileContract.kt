package io.github.alagga.gonesmart

import java.security.MessageDigest
import java.util.Locale

enum class AudioProfileSection {
    EQUALIZER,
    EFFECTS
}

enum class AudioProfileValueType {
    BOOLEAN,
    NUMBER,
    CHOICE
}

data class AudioProfileField(
    val id: String,
    val title: String,
    val subtitle: String,
    val valueType: AudioProfileValueType,
    val unit: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val suggestedDefault: String,
    val choices: List<String> = emptyList()
) {
    fun normalize(raw: String): String? {
        return when (valueType) {
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

            AudioProfileValueType.CHOICE -> raw.trim().takeIf { it.isNotEmpty() }
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

data class AudioProfileGroup(
    val id: String,
    val section: AudioProfileSection,
    val title: String,
    val subtitle: String,
    val fields: List<AudioProfileField>
)

data class AudioRouteProfile(
    val id: String,
    val label: String,
    val bluetooth: Boolean,
    val lastSeenEpochMs: Long,
    val active: Boolean = false
)

object GoneSmartAudioProfiles {
    // v2 intentionally starts with a clean development store because v1 used
    // per-field override markers and unstable Bluetooth route identities.
    const val GROUP = "audio_profiles_v2"
    const val LOCAL_ROUTE_ID = "local"
    const val LOCAL_ROUTE_LABEL = "No Bluetooth device"
    const val CUSTOM_PRESET = "Custom"

    const val KEY_ACTIVE_ROUTE = "route.active"
    const val KEY_SCHEMA_VERSION = "schema.version"
    const val SCHEMA_VERSION = 2

    private const val ROUTE_PREFIX = "route."
    private const val OVERRIDE_PREFIX = "override."
    private const val VALUE_PREFIX = "value."
    private const val NATIVE_PREFIX = "native."
    private const val NATIVE_PRESET_PREFIX = "native_preset."

    /** Built-in names exposed by GMMP. Custom is intentionally not a preset. */
    val presetSuggestions: List<String> = listOf(
        "Flat",
        "Rock",
        "Dance",
        "Pop",
        "Live",
        "High Boost",
        "Mid Boost",
        "Bass Boost",
        "Bass Max",
        "Bass & Treble",
        "Classical",
        "Techno",
        "Club",
        "Reggae",
        "Soft",
        "Rap",
        "Metal"
    )

    val equalizerBandFieldIds: List<String> = listOf(
        "eq.band_32_db",
        "eq.band_64_db",
        "eq.band_128_db",
        "eq.band_256_db",
        "eq.band_512_db",
        "eq.band_1000_db",
        "eq.band_2000_db",
        "eq.band_4000_db",
        "eq.band_8000_db",
        "eq.band_16000_db"
    )

    val equalizerShapeFieldIds: List<String> = listOf("eq.pre_db") + equalizerBandFieldIds

    private fun eqBand(frequency: Int, label: String) = AudioProfileField(
        id = "eq.band_${frequency}_db",
        title = label,
        subtitle = "Band gain",
        valueType = AudioProfileValueType.NUMBER,
        unit = "dB",
        minimum = -12.0,
        maximum = 12.0,
        step = 0.1,
        suggestedDefault = "0"
    )

    val equalizerGroup = AudioProfileGroup(
        id = "equalizer",
        section = AudioProfileSection.EQUALIZER,
        title = "Equalizer",
        subtitle = "Override the complete GMMP equalizer for this output. Preset, PRE and all bands belong to this single override.",
        fields = listOf(
            AudioProfileField(
                id = "eq.enabled",
                title = "Enabled",
                subtitle = "Use the GMMP equalizer for this output.",
                valueType = AudioProfileValueType.BOOLEAN,
                suggestedDefault = "false"
            ),
            AudioProfileField(
                id = "eq.preset",
                title = "Preset",
                subtitle = "Choose a native GMMP preset. Changing PRE or a band turns the profile into Custom.",
                valueType = AudioProfileValueType.CHOICE,
                suggestedDefault = "Flat",
                choices = presetSuggestions
            ),
            AudioProfileField(
                id = "eq.pre_db",
                title = "PRE",
                subtitle = "GMMP's preamp gain control.",
                valueType = AudioProfileValueType.NUMBER,
                unit = "dB",
                minimum = -12.0,
                maximum = 12.0,
                step = 0.1,
                suggestedDefault = "0"
            ),
            eqBand(32, "32 Hz"),
            eqBand(64, "64 Hz"),
            eqBand(128, "128 Hz"),
            eqBand(256, "256 Hz"),
            eqBand(512, "512 Hz"),
            eqBand(1000, "1 kHz"),
            eqBand(2000, "2 kHz"),
            eqBand(4000, "4 kHz"),
            eqBand(8000, "8 kHz"),
            eqBand(16000, "16 kHz")
        )
    )

    val effectGroups: List<AudioProfileGroup> = listOf(
        AudioProfileGroup(
            id = "balance",
            section = AudioProfileSection.EFFECTS,
            title = "Balance",
            subtitle = "Enabled state and left/right amount are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "balance.enabled",
                    title = "Enabled",
                    subtitle = "Enable balance adjustment.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                ),
                AudioProfileField(
                    id = "balance.percent",
                    title = "Amount",
                    subtitle = "0 keeps both channels equal.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "%",
                    minimum = -100.0,
                    maximum = 100.0,
                    step = 1.0,
                    suggestedDefault = "0"
                )
            )
        ),
        AudioProfileGroup(
            id = "mono",
            section = AudioProfileSection.EFFECTS,
            title = "Force mono",
            subtitle = "Override GMMP's mono downmix state.",
            fields = listOf(
                AudioProfileField(
                    id = "mono.enabled",
                    title = "Enabled",
                    subtitle = "Downmix multi-channel audio to mono.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                )
            )
        ),
        AudioProfileGroup(
            id = "limiter",
            section = AudioProfileSection.EFFECTS,
            title = "Limiter",
            subtitle = "Enabled state, attack and release are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "limiter.enabled",
                    title = "Enabled",
                    subtitle = "Enable GMMP's limiter.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "true"
                ),
                AudioProfileField(
                    id = "limiter.attack_ms",
                    title = "Attack",
                    subtitle = "Limiter attack time.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "ms",
                    minimum = 0.0,
                    step = 1.0,
                    suggestedDefault = "0"
                ),
                AudioProfileField(
                    id = "limiter.release_ms",
                    title = "Release",
                    subtitle = "Limiter release time.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "ms",
                    minimum = 0.0,
                    step = 1.0,
                    suggestedDefault = "500"
                )
            )
        ),
        AudioProfileGroup(
            id = "bass_boost",
            section = AudioProfileSection.EFFECTS,
            title = "Bass boost",
            subtitle = "Enabled state and strength are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "bass.enabled",
                    title = "Enabled",
                    subtitle = "Enable Android's bass boost effect through GMMP.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                ),
                AudioProfileField(
                    id = "bass.strength_percent",
                    title = "Strength",
                    subtitle = "Bass boost strength.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "%",
                    minimum = 0.0,
                    maximum = 100.0,
                    step = 1.0,
                    suggestedDefault = "100"
                )
            )
        ),
        AudioProfileGroup(
            id = "tempo",
            section = AudioProfileSection.EFFECTS,
            title = "Tempo",
            subtitle = "Enabled state and playback speed are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "tempo.enabled",
                    title = "Enabled",
                    subtitle = "Enable tempo adjustment.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                ),
                AudioProfileField(
                    id = "tempo.percent",
                    title = "Tempo",
                    subtitle = "Playback speed relative to normal speed.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "%",
                    minimum = 50.0,
                    maximum = 200.0,
                    step = 1.0,
                    suggestedDefault = "100"
                )
            )
        ),
        AudioProfileGroup(
            id = "pitch_correction",
            section = AudioProfileSection.EFFECTS,
            title = "Auto pitch correction",
            subtitle = "Override GMMP's automatic pitch correction state.",
            fields = listOf(
                AudioProfileField(
                    id = "pitch_correction.enabled",
                    title = "Enabled",
                    subtitle = "Correct pitch when tempo is changed.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "true"
                )
            )
        ),
        AudioProfileGroup(
            id = "virtualizer",
            section = AudioProfileSection.EFFECTS,
            title = "Virtualizer",
            subtitle = "Enabled state and strength are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "virtualizer.enabled",
                    title = "Enabled",
                    subtitle = "Enable the virtualizer effect.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                ),
                AudioProfileField(
                    id = "virtualizer.strength_percent",
                    title = "Strength",
                    subtitle = "Virtualizer strength.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "%",
                    minimum = 0.0,
                    maximum = 100.0,
                    step = 1.0,
                    suggestedDefault = "0"
                )
            )
        ),
        AudioProfileGroup(
            id = "amplifier",
            section = AudioProfileSection.EFFECTS,
            title = "Amplifier",
            subtitle = "Enabled state and gain are one override.",
            fields = listOf(
                AudioProfileField(
                    id = "amplifier.enabled",
                    title = "Enabled",
                    subtitle = "Enable GMMP's amplifier.",
                    valueType = AudioProfileValueType.BOOLEAN,
                    suggestedDefault = "false"
                ),
                AudioProfileField(
                    id = "amplifier.gain_db",
                    title = "Gain",
                    subtitle = "Amplifier gain.",
                    valueType = AudioProfileValueType.NUMBER,
                    unit = "dB",
                    step = 0.1,
                    suggestedDefault = "0"
                )
            )
        )
    )

    val allGroups: List<AudioProfileGroup> = listOf(equalizerGroup) + effectGroups
    val allFields: List<AudioProfileField> = allGroups.flatMap { it.fields }

    fun group(id: String): AudioProfileGroup? = allGroups.firstOrNull { it.id == id }
    fun field(id: String): AudioProfileField? = allFields.firstOrNull { it.id == id }

    fun isEqualizerShapeField(fieldId: String): Boolean = fieldId in equalizerShapeFieldIds

    fun overrideKey(routeId: String, groupId: String): String =
        "$OVERRIDE_PREFIX$routeId.$groupId"

    fun valueKey(routeId: String, fieldId: String): String =
        "$VALUE_PREFIX$routeId.$fieldId"

    fun nativeValueKey(fieldId: String): String = "$NATIVE_PREFIX$fieldId"

    fun nativePresetValueKey(presetName: String, fieldId: String): String =
        "$NATIVE_PRESET_PREFIX${presetStorageId(presetName)}.$fieldId"

    fun nativePresetNameKey(presetName: String): String =
        "$NATIVE_PRESET_PREFIX${presetStorageId(presetName)}.name"

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
        val normalizedLabel = label
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.lowercase(Locale.US)
            .orEmpty()
        val normalizedAddress = address?.trim()?.lowercase(Locale.US).orEmpty()
        // A product name is not a physical-device identifier: two paired
        // receivers may advertise the same model name. Prefer the address,
        // independent of transport/type (A2DP, LE, etc.) for the same device.
        // Where Android hides the address, retain the legacy fallback instead
        // of inventing an unstable identifier from AudioDeviceInfo.id.
        val identity = if (normalizedAddress.isNotBlank()) {
            "address=$normalizedAddress"
        } else {
            normalizedLabel.ifBlank { "type=$type|address=unknown" }
        }
        return "bt_" + sha256Token(identity)
    }

    private fun presetStorageId(presetName: String): String =
        sha256Token(presetName.trim().lowercase(Locale.US))

    private fun sha256Token(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { byte -> "%02x".format(byte) }
    }
}
