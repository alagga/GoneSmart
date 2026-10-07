package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMixInitialFillPolicyTest {
    @Test fun requestsInitialSizeMinusNativeSeedExactlyOnce() {
        assertEquals(
            4,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 1
            )
        )
    }

    @Test fun doesNotRequestAnythingWhenSeedAlreadyMeetsTarget() {
        assertEquals(
            0,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 5
            )
        )
        assertEquals(
            0,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 7
            )
        )
    }

    @Test fun clampsInvalidSettingsToSafeCounts() {
        assertEquals(
            1,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 0,
                seedQueueSize = 0
            )
        )
        assertEquals(
            5,
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = -3
            )
        )
    }
}
