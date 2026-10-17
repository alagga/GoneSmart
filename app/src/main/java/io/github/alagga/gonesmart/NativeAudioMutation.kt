package io.github.alagga.gonesmart

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reverse mapping from GoneSmart's normalized audio-profile values to the
 * native GMMP SharedPreferences values proven by natural 4.2.1 UI writes.
 *
 * This object is deliberately pure. It describes the accepted preference
 * boundary but does not perform any host mutation itself.
 */
object NativeAudioMutation {
    enum class Kind {
        BOOLEAN,
        INT,
        FLOAT,
        STRING
    }

    data class PreferenceWrite(
        val fieldId: String,
        val key: String,
        val kind: Kind,
        val value: Any
    )

    val directlyWritableFieldIds: Set<String> = setOf(
        "eq.enabled",
        "eq.preset",
        "balance.enabled",
        "balance.percent",
        "mono.enabled",
        "limiter.enabled",
        "limiter.attack_ms",
        "limiter.release_ms",
        "bass.enabled",
        "bass.strength_percent",
        "tempo.enabled",
        "tempo.percent",
        "pitch_correction.enabled",
        "virtualizer.enabled",
        "virtualizer.strength_percent",
        "amplifier.enabled",
        "amplifier.gain_db"
    )

    fun preferenceWrite(fieldId: String, normalizedValue: String): PreferenceWrite? {
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
        val normalized = field.normalize(normalizedValue) ?: return null

        return when (fieldId) {
            "eq.enabled" -> booleanWrite(fieldId, NativeAudioObservation.PREF_EQ_ENABLED, normalized)
            "eq.preset" -> normalized
                .takeIf { it != GoneSmartAudioProfiles.CUSTOM_PRESET }
                ?.let { PreferenceWrite(fieldId, NativeAudioObservation.PREF_EQ_PRESET, Kind.STRING, it) }

            "balance.enabled" -> booleanWrite(fieldId, "dspSettings_balanceEnabled", normalized)
            "balance.percent" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_balance", Kind.FLOAT, (it / 100.0).toFloat())
            }

            "mono.enabled" -> booleanWrite(fieldId, "player_forceMono", normalized)
            "limiter.enabled" -> booleanWrite(fieldId, "dspSettings_limiterEnabled", normalized)
            "limiter.attack_ms" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_attack", Kind.INT, it.roundToInt())
            }
            "limiter.release_ms" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_release", Kind.INT, it.roundToInt())
            }

            "bass.enabled" -> booleanWrite(fieldId, "dspSettings_bassBoostEnabled", normalized)
            "bass.strength_percent" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_bassBoostStrength", Kind.INT, (it * 10.0).roundToInt())
            }

            "tempo.enabled" -> booleanWrite(fieldId, "player_tempoEnabled", normalized)
            "tempo.percent" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "player_tempo", Kind.FLOAT, (it / 100.0).toFloat())
            }

            "pitch_correction.enabled" -> booleanWrite(fieldId, "player_autoPitchCorrection", normalized)
            "virtualizer.enabled" -> booleanWrite(fieldId, "dspSettings_virtualizerEnabled", normalized)
            "virtualizer.strength_percent" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_virtualizerStrength", Kind.INT, (it * 10.0).roundToInt())
            }

            "amplifier.enabled" -> booleanWrite(fieldId, "dspSettings_loudnessEnabled", normalized)
            "amplifier.gain_db" -> number(normalized)?.let {
                PreferenceWrite(fieldId, "dspSettings_loudnessGain", Kind.INT, (it * 100.0).roundToInt())
            }

            else -> null
        }
    }

    fun matches(actual: Any?, write: PreferenceWrite): Boolean = when (write.kind) {
        Kind.BOOLEAN -> actual is Boolean && actual == write.value
        Kind.INT -> actual is Number && actual.toInt() == write.value
        Kind.FLOAT -> actual is Number && write.value is Float &&
            abs(actual.toFloat() - write.value) <= 0.0001f
        Kind.STRING -> actual is String && actual == write.value
    }

    private fun booleanWrite(fieldId: String, key: String, normalized: String): PreferenceWrite? = when (normalized) {
        "true" -> PreferenceWrite(fieldId, key, Kind.BOOLEAN, true)
        "false" -> PreferenceWrite(fieldId, key, Kind.BOOLEAN, false)
        else -> null
    }

    private fun number(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() }
}
