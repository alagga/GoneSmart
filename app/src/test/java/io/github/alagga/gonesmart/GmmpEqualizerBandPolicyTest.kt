package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun everyGmmpBandCountFromTwoThroughTenProjectsBothWays() {
        val canonical = listOf(-8.0, -6.0, -4.0, -2.0, 0.0, 2.0, 4.0, 6.0, 8.0, 10.0)

        for (count in GmmpEqualizerBandPolicy.MIN_BANDS..GmmpEqualizerBandPolicy.MAX_BANDS) {
            assertTrue("count=$count should be supported", GmmpEqualizerBandPolicy.isSupported(count))

            val projected = GmmpEqualizerBandPolicy.project(canonical, count)
            assertNotNull("10->$count projection should exist", projected)
            projected!!
            assertEquals("10->$count should expose exactly the active GMMP bands", count, projected.size)
            assertTrue("10->$count should stay finite", projected.all(Double::isFinite))
            assertEquals(canonical.first(), projected.first(), 0.000001)
            assertEquals(canonical.last(), projected.last(), 0.000001)

            val expanded = GmmpEqualizerBandPolicy.project(projected, 10)
            assertNotNull("$count->10 expansion should exist", expanded)
            expanded!!
            assertEquals(10, expanded.size)
            assertTrue("$count->10 should stay finite", expanded.all(Double::isFinite))
            assertEquals(projected.first(), expanded.first(), 0.000001)
            assertEquals(projected.last(), expanded.last(), 0.000001)
        }

        assertFalse(GmmpEqualizerBandPolicy.isSupported(1))
        assertFalse(GmmpEqualizerBandPolicy.isSupported(11))
    }

    @Test
    fun uiGroupShowsExactlyEveryActiveGmmpBandCount() {
        for (count in GmmpEqualizerBandPolicy.MIN_BANDS..GmmpEqualizerBandPolicy.MAX_BANDS) {
            val group = GmmpEqualizerBandPolicy.uiGroup(GoneSmartAudioProfiles.equalizerGroup, count)

            assertEquals("enabled + preset + PRE + $count bands", 3 + count, group.fields.size)
            if (count == 10) {
                assertEquals("32 Hz", group.fields[3].title)
                assertEquals("16 kHz", group.fields.last().title)
            } else {
                assertEquals("Band 1", group.fields[3].title)
                assertEquals("Band $count", group.fields.last().title)
            }
        }
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
