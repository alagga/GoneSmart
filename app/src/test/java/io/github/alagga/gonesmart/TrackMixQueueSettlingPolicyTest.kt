package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixQueueSettlingPolicyTest {
    @Test
    fun growingNativeQueueIsNotSettledEvenWhenCurrentTrackIsUnchanged() {
        assertFalse(
            TrackMixQueueSettlingPolicy.sameQueue(
                previousTrackIds = listOf(10L, 11L),
                previousEntryIds = listOf(100L, 101L),
                previousCurrentIndex = 0,
                currentTrackIds = listOf(10L, 11L, 12L),
                currentEntryIds = listOf(100L, 101L, 102L),
                currentCurrentIndex = 0
            )
        )
    }

    @Test
    fun identicalQueueRequiresFullQuietWindow() {
        val same = TrackMixQueueSettlingPolicy.sameQueue(
            previousTrackIds = listOf(10L, 11L),
            previousEntryIds = listOf(100L, 101L),
            previousCurrentIndex = 0,
            currentTrackIds = listOf(10L, 11L),
            currentEntryIds = listOf(100L, 101L),
            currentCurrentIndex = 0
        )

        assertFalse(
            TrackMixQueueSettlingPolicy.isSettled(
                sameQueue = same,
                stableForMs = TrackMixQueueSettlingPolicy.QUIET_WINDOW_MS - 1
            )
        )
        assertTrue(
            TrackMixQueueSettlingPolicy.isSettled(
                sameQueue = same,
                stableForMs = TrackMixQueueSettlingPolicy.QUIET_WINDOW_MS
            )
        )
    }

    @Test
    fun currentIndexChangeResetsSettling() {
        assertFalse(
            TrackMixQueueSettlingPolicy.sameQueue(
                previousTrackIds = listOf(10L, 11L),
                previousEntryIds = listOf(100L, 101L),
                previousCurrentIndex = 0,
                currentTrackIds = listOf(10L, 11L),
                currentEntryIds = listOf(100L, 101L),
                currentCurrentIndex = 1
            )
        )
    }
}
