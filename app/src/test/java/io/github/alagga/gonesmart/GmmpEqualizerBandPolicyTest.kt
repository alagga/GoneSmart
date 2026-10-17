package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmmpEqualizerBandPolicyTest {

    @Test
    fun tenBandsStayExact() {
        val source = (0 until 10).map(Int::toDouble)
        assertEquals(source, GmmpEqualizerBandPolicy.project(source, 10))
    }

    @Test
    fun projectsTenBandsToFiveWithStableEndpoints() {
        val source = (0 until 10).map(Int::toDouble)
        val projected = GmmpEqualizerBandPolicy.project(source, 5)!!

        assertEquals(listOf(0.0, 2.25, 4.5, 6.75, 9.0), projected)
    }

    @Test
    fun expandsFiveBandEditBackToCanonicalTenPoints() {
        val five = listOf(-6.0, -3.0, 0.0, 3.0, 6.0)
        val expanded = GmmpEqualizerBandPolicy.project(five, 10)!!

        assertEquals(10, expanded.size)
        assertEquals(-6.0, expanded.first(), 0.000001)
        assertEquals(6.0, expanded.last(), 0.000001)
        assertEquals(3.333333333333333, expanded[7], 0.000001)
    }

    @Test
    fun uiGroupShowsOnlyActiveBandCount() {
        val fiveBand = GmmpEqualizerBandPolicy.uiGroup(GoneSmartAudioProfiles.equalizerGroup, 5)
        val tenBand = GmmpEqualizerBandPolicy.uiGroup(GoneSmartAudioProfiles.equalizerGroup, 10)

        assertEquals(8, fiveBand.fields.size)
        assertEquals("Band 1", fiveBand.fields[3].title)
        assertEquals(13, tenBand.fields.size)
        assertEquals("32 Hz", tenBand.fields[3].title)
    }

    @Test
    fun rejectsUnsupportedCounts() {
        assertNull(GmmpEqualizerBandPolicy.project(listOf(0.0), 10))
        assertNull(GmmpEqualizerBandPolicy.project(List(10) { 0.0 }, 1))
        assertNull(GmmpEqualizerBandPolicy.project(List(11) { 0.0 }, 10))
    }
}
