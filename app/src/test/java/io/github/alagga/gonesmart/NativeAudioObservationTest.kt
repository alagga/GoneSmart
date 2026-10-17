package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAudioObservationTest {

    @Test
    fun mapsEqualizerPreferenceStateWithoutInventingValues() {
        assertEquals(
            NativeAudioObservation.Value("eq.enabled", "false"),
            NativeAudioObservation.fromPreference("player_eqEnabled", false)
        )
        assertEquals(
            NativeAudioObservation.Value("eq.preset", "Club"),
            NativeAudioObservation.fromPreference("dspSettings_activePreset", " Club ")
        )
        assertNull(NativeAudioObservation.fromPreference("dspSettings_activePreset", "  "))
    }

    @Test
    fun mapsEffectStorageUnitsToUiUnits() {
        assertEquals(
            NativeAudioObservation.Value("balance.percent", "35"),
            NativeAudioObservation.fromPreference("dspSettings_balance", 0.35f)
        )
        assertEquals(
            NativeAudioObservation.Value("bass.strength_percent", "47"),
            NativeAudioObservation.fromPreference("dspSettings_bassBoostStrength", 470)
        )
        assertEquals(
            NativeAudioObservation.Value("tempo.percent", "63"),
            NativeAudioObservation.fromPreference("player_tempo", 0.63f)
        )
        assertEquals(
            NativeAudioObservation.Value("virtualizer.strength_percent", "39"),
            NativeAudioObservation.fromPreference("dspSettings_virtualizerStrength", 390)
        )
        assertEquals(
            NativeAudioObservation.Value("amplifier.gain_db", "-4.4"),
            NativeAudioObservation.fromPreference("dspSettings_loudnessGain", -440)
        )
    }

    @Test
    fun mapsNativeEqualizerSettersToPreAndBands() {
        assertEquals(
            NativeAudioObservation.Value("eq.pre_db", "1.3"),
            NativeAudioObservation.equalizerPre(1.26)
        )
        assertEquals(
            NativeAudioObservation.Value("eq.band_32_db", "-3.9"),
            NativeAudioObservation.equalizerBand(0, -3.94)
        )
        assertEquals(
            NativeAudioObservation.Value("eq.band_16000_db", "2.1"),
            NativeAudioObservation.equalizerBand(9, 2.08)
        )
        assertNull(NativeAudioObservation.equalizerBand(10, 1.0))
    }

    @Test
    fun ignoresUnknownPreferenceKeysAndInvalidValues() {
        assertNull(NativeAudioObservation.fromPreference("unrelated", true))
        assertNull(NativeAudioObservation.fromPreference("player_tempo", "nope"))
        assertNull(NativeAudioObservation.equalizerPre(Double.NaN))
    }
}
