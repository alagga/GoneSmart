package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioMutationTest {

    @Test
    fun effectWritesRoundTripThroughObservedNativeUnits() {
        val cases = listOf(
            "balance.enabled" to "true",
            "balance.percent" to "35",
            "mono.enabled" to "true",
            "limiter.enabled" to "false",
            "limiter.attack_ms" to "35",
            "limiter.release_ms" to "320",
            "bass.enabled" to "true",
            "bass.strength_percent" to "47",
            "tempo.enabled" to "true",
            "tempo.percent" to "63",
            "pitch_correction.enabled" to "true",
            "virtualizer.enabled" to "true",
            "virtualizer.strength_percent" to "39",
            "amplifier.enabled" to "true",
            "amplifier.gain_db" to "-4.4"
        )

        cases.forEach { (fieldId, normalized) ->
            val write = NativeAudioMutation.preferenceWrite(fieldId, normalized)
                ?: error("Missing mutation mapping for $fieldId")
            val observed = NativeAudioObservation.fromPreference(write.key, write.value)
                ?: error("Native write did not map back for $fieldId")
            assertEquals(fieldId, observed.fieldId)
            assertEquals(GoneSmartAudioProfiles.field(fieldId)?.normalize(normalized), observed.value)
            assertTrue(NativeAudioMutation.matches(write.value, write))
        }
    }

    @Test
    fun equalizerUsesNativeEnableAndNamedPresetPreferences() {
        val enabled = NativeAudioMutation.preferenceWrite("eq.enabled", "true")
        assertEquals(NativeAudioObservation.PREF_EQ_ENABLED, enabled?.key)
        assertEquals(NativeAudioMutation.Kind.BOOLEAN, enabled?.kind)
        assertEquals(true, enabled?.value)

        val preset = NativeAudioMutation.preferenceWrite("eq.preset", "Club")
        assertEquals(NativeAudioObservation.PREF_EQ_PRESET, preset?.key)
        assertEquals(NativeAudioMutation.Kind.STRING, preset?.kind)
        assertEquals("Club", preset?.value)
    }

    @Test
    fun customPresetAndEqShapeAreNotInventedAsPreferenceWrites() {
        assertNull(NativeAudioMutation.preferenceWrite("eq.preset", GoneSmartAudioProfiles.CUSTOM_PRESET))
        assertNull(NativeAudioMutation.preferenceWrite("eq.pre_db", "1.2"))
        assertNull(NativeAudioMutation.preferenceWrite("eq.band_32_db", "2.0"))
    }
}
