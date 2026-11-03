package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAutoDjContinuationPolicyTest {
    @Test fun `managed track mix waits until the last queue row becomes current`() {
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(5, 2, 1))
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(5, 3, 1))
        assertEquals(1, TrackAutoDjContinuationPolicy.refillCount(5, 4, 1))
    }

    @Test fun `uses configured upcoming without overfilling`() {
        assertEquals(2, TrackAutoDjContinuationPolicy.refillCount(5, 4, 2))
        assertEquals(4, TrackAutoDjContinuationPolicy.refillCount(5, 4, 4))
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(5, 3, 2))
    }

    @Test fun `invalid current index fails closed`() {
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(5, -1, 1))
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(5, 5, 1))
        assertEquals(0, TrackAutoDjContinuationPolicy.refillCount(0, 0, 1))
    }
}
