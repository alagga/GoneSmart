package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAutoDjContinuationPolicyTest {
    @Test
    fun `keeps a safety margin even if a single upcoming track exists`() {
        assertEquals(
            0,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 1,
                upcomingTrackCount = 1
            )
        )
    }

    @Test
    fun `refills three tracks at current queue tail to avoid late source binding`() {
        assertEquals(
            3,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 4,
                upcomingTrackCount = 1
            )
        )
    }

    @Test
    fun `requests only the missing upcoming deficit`() {
        assertEquals(
            2,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 3,
                upcomingTrackCount = 2
            )
        )
        assertEquals(
            3,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 4,
                upcomingTrackCount = 2
            )
        )
    }

    @Test
    fun `invalid current index fails closed`() {
        assertEquals(
            0,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = -1,
                upcomingTrackCount = 1
            )
        )
    }
}
