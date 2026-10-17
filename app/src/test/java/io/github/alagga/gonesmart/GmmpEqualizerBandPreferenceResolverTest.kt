package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerBandPreferenceResolverTest {

    @Test
    fun resolvesUniqueEqBandCountCandidate() {
        val candidate = GmmpEqualizerBandPreferenceResolver.resolve(
            mapOf(
                "player_eqEnabled" to true,
                "dspSettings_numEqBands" to 3,
                "dspSettings_bassBoostStrength" to 1000
            )
        )

        assertEquals("dspSettings_numEqBands", candidate?.key)
        assertEquals(3, candidate?.count)
    }

    @Test
    fun acceptsCommonSemanticSpellingsAndStringValues() {
        listOf(
            "eqBands",
            "player_eqBandCount",
            "dspSettings_bands",
            "dspSettings_bandCount"
        ).forEach { key ->
            val candidate = GmmpEqualizerBandPreferenceResolver.resolve(mapOf(key to "10"))
            assertEquals(key, candidate?.key)
            assertEquals(10, candidate?.count)
        }
    }

    @Test
    fun unrelatedSmallIntegersDoNotQualify() {
        assertNull(
            GmmpEqualizerBandPreferenceResolver.resolve(
                mapOf(
                    "crossfade_count" to 3,
                    "dspSettings_attack" to 2,
                    "player_bufferCount" to 4
                )
            )
        )
    }

    @Test
    fun ambiguityFailsClosed() {
        assertNull(
            GmmpEqualizerBandPreferenceResolver.resolve(
                mapOf(
                    "dspSettings_numEqBands" to 5,
                    "player_eqBandCount" to 5
                )
            )
        )
    }

    @Test
    fun unsupportedAndFractionalCountsFailClosed() {
        assertNull(GmmpEqualizerBandPreferenceResolver.resolve(mapOf("dspSettings_numEqBands" to 1)))
        assertNull(GmmpEqualizerBandPreferenceResolver.resolve(mapOf("dspSettings_numEqBands" to 11)))
        assertNull(GmmpEqualizerBandPreferenceResolver.resolve(mapOf("dspSettings_numEqBands" to 3.5)))
    }

    @Test
    fun semanticKeyFilterRequiresBandAndEqOrDspCountContext() {
        assertTrue(GmmpEqualizerBandPreferenceResolver.looksLikeEqualizerBandCountKey("dspSettings_numBands"))
        assertTrue(GmmpEqualizerBandPreferenceResolver.looksLikeEqualizerBandCountKey("player_eqBandCount"))
    }
}
