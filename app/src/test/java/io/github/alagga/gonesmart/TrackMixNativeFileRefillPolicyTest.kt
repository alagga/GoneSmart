package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeFileRefillPolicyTest {
    @Test
    fun nativeStartRequiresTestedQueueAndAbsoluteFile() {
        assertTrue(TrackMixNativeFileRefillPolicy.canStart(false, "/storage/Music/a.mp3", 5))
        assertFalse(TrackMixNativeFileRefillPolicy.canStart(true, "/storage/Music/a.mp3", 5))
        assertFalse(TrackMixNativeFileRefillPolicy.canStart(false, "content://song", 5))
        assertFalse(TrackMixNativeFileRefillPolicy.canStart(false, null, 5))
    }

    @Test
    fun acceptsManualQueueAdditionsAndReorderingButRejectsNewPlayback() {
        assertTrue(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(10L, 99L, 88L), listOf(101L, 102L, 103L),
            listOf(1, 2, 3), 10L, 101L
        ))
        assertTrue(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(88L, 10L, 99L), listOf(103L, 101L, 102L),
            listOf(1, 2, 3), 10L, 101L
        ))
        assertFalse(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(88L), listOf(103L), listOf(1), 10L, 101L
        ))
        assertFalse(TrackMixNativeFileRefillPolicy.hasOriginalSessionAnchor(
            listOf(10L, 99L), listOf(101L, 102L), listOf(1, 9), 10L, 101L
        ))
    }

    @Test
    fun verifiesNativeSmartFollowersAndPreservesAllManualEntries() {
        assertEquals(listOf(30L, 40L), TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTrackIds = listOf(10L, 99L),
            beforeEntryIds = listOf(101L, 102L),
            beforePositions = listOf(1, 2),
            afterTrackIds = listOf(10L, 99L, 30L, 40L),
            afterEntryIds = listOf(101L, 102L, 103L, 104L),
            afterPositions = listOf(1, 2, 3, 4),
            currentBefore = 101L,
            currentAfter = 101L,
            requested = 2,
            preparedTrackIds = listOf(30L, 40L, 50L)
        ))
    }

    @Test
    fun rejectsNativeRefillThatDiscardsOrRewritesManualTracks() {
        fun verify(afterTracks: List<Long>, afterEntries: List<Long>): List<Long>? =
            TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
                beforeTrackIds = listOf(10L, 99L),
                beforeEntryIds = listOf(101L, 102L),
                beforePositions = listOf(1, 2),
                afterTrackIds = afterTracks,
                afterEntryIds = afterEntries,
                afterPositions = (1..afterEntries.size).toList(),
                currentBefore = 101L,
                currentAfter = 101L,
                requested = 2,
                preparedTrackIds = listOf(30L, 40L)
            )
        assertNull(verify(listOf(10L, 30L), listOf(101L, 103L)))
        assertNull(verify(listOf(10L, 88L, 30L), listOf(101L, 102L, 103L)))
        assertNull(verify(listOf(99L, 10L, 30L), listOf(102L, 101L, 103L)))
    }

    @Test
    fun rejectsDuplicatesUnpreparedChoicesAndOverfill() {
        fun verify(afterTracks: List<Long>): List<Long>? =
            TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
                beforeTrackIds = listOf(10L),
                beforeEntryIds = listOf(101L),
                beforePositions = listOf(1),
                afterTrackIds = afterTracks,
                afterEntryIds = (101L until 101L + afterTracks.size).toList(),
                afterPositions = (1..afterTracks.size).toList(),
                currentBefore = 101L,
                currentAfter = 101L,
                requested = 2,
                preparedTrackIds = listOf(30L, 40L)
            )
        assertNull(verify(listOf(10L, 30L, 30L)))
        assertNull(verify(listOf(10L, 77L)))
        assertNull(verify(listOf(10L, 30L, 40L, 50L)))
        assertNull(verify(listOf(10L)))
    }

    @Test
    fun nativeRefillCanAppendAfterManualReorderingDuringSearch() {
        assertEquals(listOf(30L), TrackMixNativeFileRefillPolicy.verifiedSmartRefill(
            beforeTrackIds = listOf(99L, 10L),
            beforeEntryIds = listOf(102L, 101L),
            beforePositions = listOf(1, 2),
            afterTrackIds = listOf(99L, 10L, 30L),
            afterEntryIds = listOf(102L, 101L, 103L),
            afterPositions = listOf(1, 2, 3),
            currentBefore = 102L,
            currentAfter = 102L,
            requested = 2,
            preparedTrackIds = listOf(30L, 40L)
        ))
    }

    @Test
    fun initialReserveFollowsGmmpSettings() {
        assertEquals(4, TrackMixNativeFileRefillPolicy.neededInitialFollowers(5, 1))
        assertEquals(3, TrackMixNativeFileRefillPolicy.neededInitialFollowers(2, 3))
        assertEquals(1, TrackMixNativeFileRefillPolicy.neededInitialFollowers(1, 0))
    }
}
