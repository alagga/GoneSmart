package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeFileRefillPolicyTest {
    @Test fun `initial track mix requires tested native queue and absolute path`() {
        assertTrue(TrackMixNativeFileRefillPolicy.canStart(false, "/Music/a.mp3", 5))
        assertFalse(TrackMixNativeFileRefillPolicy.canStart(true, "/Music/a.mp3", 5))
        assertFalse(TrackMixNativeFileRefillPolicy.canStart(false, "content://a", 5))
    }

    @Test fun `manual queue entries may be reordered without losing original seed`() {
        assertTrue(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(99L, 10L), listOf(102L, 101L), listOf(1, 2), 10L, 101L
        ))
        assertFalse(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(99L), listOf(102L), listOf(1), 10L, 101L
        ))
    }

    @Test fun `native partial refill preserves current and manually queued songs`() {
        assertEquals(listOf(30L, 40L), TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTrackIds = listOf(10L, 99L),
            beforeEntryIds = listOf(101L, 102L),
            beforePositions = listOf(1, 2),
            afterTrackIds = listOf(10L, 99L, 30L, 40L),
            afterEntryIds = listOf(101L, 102L, 103L, 104L),
            afterPositions = listOf(1, 2, 3, 4),
            currentBefore = 101L, currentAfter = 101L, requested = 4,
            preparedTrackIds = listOf(30L, 40L, 50L)
        ))
    }

    @Test fun `native fallback cannot pad a partial smart refill`() {
        assertNull(TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTrackIds = listOf(10L), beforeEntryIds = listOf(101L),
            beforePositions = listOf(1),
            afterTrackIds = listOf(10L, 30L, 999L),
            afterEntryIds = listOf(101L, 102L, 103L),
            afterPositions = listOf(1, 2, 3),
            currentBefore = 101L, currentAfter = 101L, requested = 4,
            preparedTrackIds = listOf(30L)
        ))
    }

    @Test fun `native success requires actual new entries and the exact current`() {
        val beforeTracks = listOf(10L)
        val beforeEntries = listOf(101L)
        val beforePositions = listOf(1)
        assertNull(TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTracks, beforeEntries, beforePositions,
            beforeTracks, beforeEntries, beforePositions,
            101L, 101L, 4, listOf(30L)
        ))
        assertNull(TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTracks, beforeEntries, beforePositions,
            listOf(10L, 30L), listOf(101L, 102L), listOf(1, 2),
            101L, 102L, 4, listOf(30L)
        ))
    }

    @Test fun `native refill cannot rewrite a manually queued track`() {
        assertNull(TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTrackIds = listOf(10L, 99L),
            beforeEntryIds = listOf(101L, 102L),
            beforePositions = listOf(1, 2),
            afterTrackIds = listOf(10L, 100L, 30L),
            afterEntryIds = listOf(101L, 102L, 103L),
            afterPositions = listOf(1, 2, 3),
            currentBefore = 101L, currentAfter = 101L, requested = 4,
            preparedTrackIds = listOf(30L)
        ))
    }

    @Test fun `initial reserve uses native dynamic settings`() {
        assertEquals(4, TrackMixNativeFileRefillPolicy.neededInitialFollowers(5, 1))
        assertEquals(3, TrackMixNativeFileRefillPolicy.neededInitialFollowers(2, 3))
    }
}
