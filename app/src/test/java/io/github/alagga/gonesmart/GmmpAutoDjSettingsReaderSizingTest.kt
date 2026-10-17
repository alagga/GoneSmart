package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class GmmpAutoDjSettingsReaderSizingTest {
    private val reader = GmmpAutoDjSettingsReader()

    @Test
    fun currentFiveTrackInitialSizeKeepsTwelveCandidateRescueReserve() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 5,
                upcomingTrackCount = 1,
                source = "test"
            )
        )

        assertEquals(20, sizing.targetSize)
        assertEquals(12, sizing.rescueCandidateTarget)
    }

    @Test
    fun largerInitialQueueScalesRescueGate() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 10,
                upcomingTrackCount = 1,
                source = "test"
            )
        )

        assertEquals(30, sizing.targetSize)
        assertEquals(18, sizing.rescueCandidateTarget)
    }

    @Test
    fun rescueGateNeverExceedsInternalPoolTarget() {
        val sizing = reader.calculatePoolSizing(
            GmmpAutoDjSettings(
                initialQueueSize = 5,
                upcomingTrackCount = 20,
                source = "test"
            )
        )

        assertEquals(30, sizing.targetSize)
        assertEquals(30, sizing.rescueCandidateTarget)
    }
}
