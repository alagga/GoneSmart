package io.github.alagga.gonesmart

/**
 * Pure mapping between the GMMP 4.2.1 preference/runtime values observed on
 * device and the normalized values displayed by GoneSmart.
 *
 * This object never mutates GMMP. It exists so the Xposed-side observer and
 * host tests share the same conversion rules.
 */
object NativeAudioObservation {
    data class Value(val fieldId: String, val value: String)

    const val PREF_EQ_ENABLED = "player_eqEnabled"
    const val PREF_EQ_PRESET = "dspSettings_activePreset"

    val preferenceKeys: Set<String> = setOf(
        PREF_EQ_ENABLED,
        PREF_EQ_PRESET,
        "dspSettings_balanceEnabled",
        "dspSettings_balance",
        "player_forceMono",
        "dspSettings_limiterEnabled",
        "dspSettings_attack",
        "dspSettings_release",
        "dspSettings_bassBoostEnabled",
        "dspSettings_bassBoostStrength",
        "player_tempoEnabled",
        "player_tempo",
        "player_autoPitchCorrection",
        "dspSettings_virtualizerEnabled",
        "dspSettings_virtualizerStrength",
        "dspSettings_loudnessEnabled",
        "dspSettings_loudnessGain"
    )

    fun fromPreference(key: String, raw: Any?): Value? {
        val mapped = when (key) {
            PREF_EQ_ENABLED -> "eq.enabled" to boolean(raw)
            PREF_EQ_PRESET -> "eq.preset" to (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }
            "dspSettings_balanceEnabled" -> "balance.enabled" to boolean(raw)
            "dspSettings_balance" -> "balance.percent" to number(raw)?.let { format(it * 100.0) }
            "player_forceMono" -> "mono.enabled" to boolean(raw)
            "dspSettings_limiterEnabled" -> "limiter.enabled" to boolean(raw)
            "dspSettings_attack" -> "limiter.attack_ms" to number(raw)?.let(::format)
            "dspSettings_release" -> "limiter.release_ms" to number(raw)?.let(::format)
            "dspSettings_bassBoostEnabled" -> "bass.enabled" to boolean(raw)
            "dspSettings_bassBoostStrength" -> "bass.strength_percent" to number(raw)?.let { format(it / 10.0) }
            "player_tempoEnabled" -> "tempo.enabled" to boolean(raw)
            "player_tempo" -> "tempo.percent" to number(raw)?.let { format(it * 100.0) }
            "player_autoPitchCorrection" -> "pitch_correction.enabled" to boolean(raw)
            "dspSettings_virtualizerEnabled" -> "virtualizer.enabled" to boolean(raw)
            "dspSettings_virtualizerStrength" -> "virtualizer.strength_percent" to number(raw)?.let { format(it / 10.0) }
            "dspSettings_loudnessEnabled" -> "amplifier.enabled" to boolean(raw)
            // The native Effects slider persists hundredths of a dB: e.g.
            // -440 while the UI is at -4.4 dB.
            "dspSettings_loudnessGain" -> "amplifier.gain_db" to number(raw)?.let { format(it / 100.0) }
            else -> null
        } ?: return null

        val value = mapped.second ?: return null
        val field = GoneSmartAudioProfiles.field(mapped.first) ?: return null
        return field.normalize(value)?.let { Value(mapped.first, it) }
    }

    fun equalizerPre(raw: Double): Value? =
        normalizeNumber("eq.pre_db", raw)

    fun equalizerBand(index: Int, raw: Double): Value? {
        val fieldId = GoneSmartAudioProfiles.equalizerBandFieldIds.getOrNull(index) ?: return null
        return normalizeNumber(fieldId, raw)
    }

    private fun normalizeNumber(fieldId: String, raw: Double): Value? {
        if (!raw.isFinite()) return null
        val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
        return field.normalize(format(raw))?.let { Value(fieldId, it) }
    }

    private fun boolean(raw: Any?): String? = when (raw) {
        is Boolean -> raw.toString()
        is Number -> (raw.toInt() != 0).toString()
        is String -> when (raw.trim().lowercase()) {
            "true", "1", "on" -> "true"
            "false", "0", "off" -> "false"
            else -> null
        }
        else -> null
    }

    private fun number(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun format(value: Double): String = AudioProfileField.formatNumber(value)
}
