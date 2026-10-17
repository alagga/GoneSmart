package io.github.alagga.gonesmart

import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpEqualizerBandPolicyTest {

    private val expectedFrequencies = mapOf(
        2 to listOf(350.0, 15_000.0),
        3 to listOf(150.0, 1_000.0, 15_000.0),
        4 to listOf(70.0, 400.0, 2_000.0, 15_000.0),
        5 to listOf(64.0, 256.0, 1_000.0, 4_000.0, 16_000.0),
        6 to listOf(45.0, 150.0, 450.0, 1_000.0, 4_000.0, 15_000.0),
        7 to listOf(40.0, 100.0, 300.0, 750.0, 2_000.0, 5_000.0, 15_000.0),
        8 to listOf(35.0, 85.0, 200.0, 500.0, 1_000.0, 3_000.0, 6_000.0, 15_000.0),
        9 to listOf(35.0, 70.0, 150.0, 350.0, 700.0, 1_000.0, 3_000.0, 7_000.0, 15_000.0),
        10 to listOf(32.0, 64.0, 128.0, 256.0, 512.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    )

    @Test
    fun everyGmmpBandLayoutMatchesObserved421Ui() {
        expectedFrequencies.forEach { (count, expected) ->
            assertEquals("count=$count", expected, GmmpEqualizerBandPolicy.frequenciesFor(count))
        }
    }

    @Test
    fun tenBandsStayExact() {
        val source = (0 until 10).map(Int::toDouble)
        assertEquals(source, GmmpEqualizerBandPolicy.project(source, 10))
    }

    @Test
    fun tenToFiveUsesTheExactSharedFrequencyAnchors() {
        val source = (0 until 10).map(Int::toDouble)
        val projected = GmmpEqualizerBandPolicy.project(source, 5)!!

        assertEquals(listOf(1.0, 3.0, 5.0, 7.0, 9.0), projected)
    }

    @Test
    fun fiveToTenKeepsExactAnchorsAndClampsOnlyOutsideItsRange() {
        val five = listOf(-6.0, -3.0, 0.0, 3.0, 6.0)
        val expanded = GmmpEqualizerBandPolicy.project(five, 10)!!

        assertEquals(10, expanded.size)
        assertEquals(-6.0, expanded[0], 0.000001) // 32 Hz, below 5-band range
        assertEquals(-6.0, expanded[1], 0.000001) // 64 Hz exact
        assertEquals(-3.0, expanded[3], 0.000001) // 256 Hz exact
        assertEquals(0.0, expanded[5], 0.000001)  // 1 kHz exact
        assertEquals(3.0, expanded[7], 0.000001)  // 4 kHz exact
        assertEquals(6.0, expanded[9], 0.000001)  // 16 kHz exact
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

            val expanded = GmmpEqualizerBandPolicy.project(projected, 10)
            assertNotNull("$count->10 expansion should exist", expanded)
            expanded!!
            assertEquals(10, expanded.size)
            assertTrue("$count->10 should stay finite", expanded.all(Double::isFinite))
        }

        assertFalse(GmmpEqualizerBandPolicy.isSupported(1))
        assertFalse(GmmpEqualizerBandPolicy.isSupported(11))
    }

    @Test
    fun everyProjectedBandValueStaysOnTheSliderStepGrid() {
        val canonical = listOf(9.0, 8.7, 7.1, 4.8, 2.6, 0.2, -1.7, -4.3, -6.8, -8.5)
        val bandField = GoneSmartAudioProfiles.field(GoneSmartAudioProfiles.equalizerBandFieldIds.first())!!
        val minimum = bandField.minimum!!
        val step = bandField.step!!

        for (count in GmmpEqualizerBandPolicy.MIN_BANDS..GmmpEqualizerBandPolicy.MAX_BANDS) {
            val projected = GmmpEqualizerBandPolicy.project(canonical, count)!!
            projected.forEachIndexed { index, value ->
                val ticks = (value - minimum) / step
                assertEquals(
                    "count=$count index=$index value=$value must be valid for the stepped Material slider",
                    round(ticks),
                    ticks,
                    0.000001
                )
            }
        }
    }

    @Test
    fun validatesOnlyCompleteIncreasingFrequencyVectors() {
        val valid = listOf(31.25, 125.0, 500.0, 2_000.0, 8_000.0)

        assertEquals(valid, GmmpEqualizerBandPolicy.validatedFrequencies(valid, 5))
        assertNull(GmmpEqualizerBandPolicy.validatedFrequencies(valid, 4))
        assertNull(GmmpEqualizerBandPolicy.validatedFrequencies(listOf(31.25, 125.0, 120.0), 3))
        assertNull(GmmpEqualizerBandPolicy.validatedFrequencies(listOf(12.0, 1_000.0), 2))
        assertNull(GmmpEqualizerBandPolicy.validatedFrequencies(listOf(31.25, Double.NaN), 2))
    }

    @Test
    fun uiUsesActualObservedFrequencyLabelsForEveryCount() {
        for (count in GmmpEqualizerBandPolicy.MIN_BANDS..GmmpEqualizerBandPolicy.MAX_BANDS) {
            val group = GmmpEqualizerBandPolicy.uiGroup(GoneSmartAudioProfiles.equalizerGroup, count)
            val expected = expectedFrequencies.getValue(count)

            assertEquals("enabled + preset + PRE + $count bands", 3 + count, group.fields.size)
            assertEquals(GmmpEqualizerBandPolicy.frequencyTitle(expected.first()), group.fields[3].title)
            assertEquals(GmmpEqualizerBandPolicy.frequencyTitle(expected.last()), group.fields.last().title)
        }
    }

    @Test
    fun verifiedDiagnosticFrequenciesCanOverrideTheKnownTable() {
        val group = GmmpEqualizerBandPolicy.uiGroup(
            GoneSmartAudioProfiles.equalizerGroup,
            3,
            listOf(31.25, 1_000.0, 16_500.0)
        )

        assertEquals(6, group.fields.size)
        assertEquals("31.25 Hz", group.fields[3].title)
        assertEquals("1 kHz", group.fields[4].title)
        assertEquals("16.5 kHz", group.fields[5].title)
    }

    @Test
    fun rejectsUnsupportedCounts() {
        assertNull(GmmpEqualizerBandPolicy.project(listOf(0.0), 10))
        assertNull(GmmpEqualizerBandPolicy.project(List(10) { 0.0 }, 1))
        assertNull(GmmpEqualizerBandPolicy.project(List(11) { 0.0 }, 10))
        assertNull(GmmpEqualizerBandPolicy.frequenciesFor(1))
        assertNull(GmmpEqualizerBandPolicy.frequenciesFor(11))
    }
}
