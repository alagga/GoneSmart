package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAutoDjContinuationPolicyTest {
    @Test
    fun `does not refill while configured upcoming track is present`() {
        assertEquals(
            0,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 3,
                upcomingTrackCount = 1
            )
        )
    }

    @Test
    fun `refills one track when current reaches queue tail`() {
        assertEquals(
            1,
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
            1,
            TrackAutoDjContinuationPolicy.refillCount(
                queueSize = 5,
                currentIndex = 3,
                upcomingTrackCount = 2
            )
        )
        assertEquals(
            2,
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
