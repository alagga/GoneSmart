package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerDspLayoutResolverTest {

    private class FakeDspManager {
        @Suppress("UNUSED_PARAMETER")
        fun direct(bands: Int, rate: Int, channels: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun wider(token: Int, bands: Int, rate: Int, channels: Int, flags: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun unrelated(a: Int, b: Int) = Unit
    }

    private val intType = Int::class.javaPrimitiveType!!

    private val direct = FakeDspManager::class.java.getDeclaredMethod(
        "direct",
        intType,
        intType,
        intType
    )

    private val wider = FakeDspManager::class.java.getDeclaredMethod(
        "wider",
        intType,
        intType,
        intType,
        intType,
        intType
    )

    @Test
    fun directGmmpTupleWorksForSmallBandCountsToo() {
        val observation = GmmpEqualizerDspLayoutResolver.infer(
            direct,
            listOf(3, 44_100, 2)
        )

        assertNotNull(observation)
        observation!!
        assertEquals(3, observation.bandCount)
        assertEquals(44_100, observation.sampleRate)
        assertEquals(2, observation.channels)
        assertEquals(0, observation.binding.bandArgIndex)
        assertEquals(1, observation.binding.rateArgIndex)
        assertEquals(2, observation.binding.channelArgIndex)
    }

    @Test
    fun widerTenBandCallIsStructurallyUnambiguous() {
        val observation = GmmpEqualizerDspLayoutResolver.infer(
            wider,
            listOf(99, 10, 44_100, 2, 99)
        )

        assertNotNull(observation)
        observation!!
        assertEquals(10, observation.bandCount)
        assertEquals(1, observation.binding.bandArgIndex)
        assertEquals(2, observation.binding.rateArgIndex)
        assertEquals(3, observation.binding.channelArgIndex)
    }

    @Test
    fun ambiguousWiderSmallBandCallFailsClosedUntilBindingIsKnown() {
        assertNull(
            GmmpEqualizerDspLayoutResolver.infer(
                wider,
                listOf(99, 3, 44_100, 2, 99)
            )
        )
    }

    @Test
    fun learnedBindingTracksLaterThreeAndTenBandSwitches() {
        val initial = GmmpEqualizerDspLayoutResolver.infer(
            wider,
            listOf(99, 10, 44_100, 2, 99)
        )!!

        val three = GmmpEqualizerDspLayoutResolver.readBound(
            initial.binding,
            wider,
            listOf(99, 3, 44_100, 2, 99)
        )
        val ten = GmmpEqualizerDspLayoutResolver.readBound(
            initial.binding,
            wider,
            listOf(99, 10, 48_000, 2, 99)
        )

        assertEquals(3, three?.bandCount)
        assertEquals(10, ten?.bandCount)
        assertEquals(48_000, ten?.sampleRate)
    }

    @Test
    fun boundReaderRejectsCallsThatNoLongerLookLikeEqualizerCreation() {
        val initial = GmmpEqualizerDspLayoutResolver.infer(
            wider,
            listOf(99, 10, 44_100, 2, 99)
        )!!

        assertNull(
            GmmpEqualizerDspLayoutResolver.readBound(
                initial.binding,
                wider,
                listOf(99, 11, 44_100, 2, 99)
            )
        )
        assertNull(
            GmmpEqualizerDspLayoutResolver.readBound(
                initial.binding,
                wider,
                listOf(99, 10, 1_000, 2, 99)
            )
        )
    }

    @Test
    fun candidateDiscoveryRequiresAtLeastThreeIntegralArguments() {
        val candidates = GmmpEqualizerDspLayoutResolver.candidateMethods(FakeDspManager::class.java)
        val names = candidates.map { it.name }.toSet()

        assertTrue("direct" in names)
        assertTrue("wider" in names)
        assertTrue("unrelated" !in names)
    }
}
