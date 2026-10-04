package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun waitsForObservedNativeRefillBeforeSupplementing() {
        assertNull(
            TrackMixInitialFillPolicy.safeSupplementCount(
                initialSize = 5,
                seedQueueSize = 1,
                observedRequestedTracks = 1,
                actualQueueSize = 1
            )
        )
    }

    @Test fun supplementsOnlyTheRemainingInitialSizeAfterNativeRefill() {
        assertEquals(
            3,
            TrackMixInitialFillPolicy.safeSupplementCount(
                initialSize = 5,
                seedQueueSize = 1,
                observedRequestedTracks = 1,
                actualQueueSize = 2
            )
        )
    }

    @Test fun requestsWholeShortageWhenNoNativeRefillWasObserved() {
        assertEquals(
            4,
            TrackMixInitialFillPolicy.safeSupplementCount(
                initialSize = 5,
                seedQueueSize = 1,
                observedRequestedTracks = 0,
                actualQueueSize = 1
            )
        )
    }

    @Test fun neverOverfillsWhenObservedRefillAlreadyReachedTarget() {
        assertEquals(
            0,
            TrackMixInitialFillPolicy.safeSupplementCount(
                initialSize = 5,
                seedQueueSize = 1,
                observedRequestedTracks = 4,
                actualQueueSize = 5
            )
        )
        assertEquals(
            5,
            TrackMixInitialFillPolicy.expectedQueueSizeAfterObservedRefill(
                initialSize = 5,
                seedQueueSize = 1,
                observedRequestedTracks = 20
            )
        )
    }
}
