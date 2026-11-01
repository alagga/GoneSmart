package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixNativeEnqueuePolicyTest {
    @Test
    fun nativeStartRequiresTestedQueueAndAbsoluteFile() {
        assertTrue(TrackMixNativeEnqueuePolicy.canStart(false, "/storage/Music/a.mp3", 5))
        assertFalse(TrackMixNativeEnqueuePolicy.canStart(true, "/storage/Music/a.mp3", 5))
        assertFalse(TrackMixNativeEnqueuePolicy.canStart(false, "content://song", 5))
        assertFalse(TrackMixNativeEnqueuePolicy.canStart(false, null, 5))
    }

    @Test
    fun acceptsManualQueueAdditionsAndReorderingButRejectsNewPlayback() {
        assertTrue(TrackMixNativeEnqueuePolicy.hasOriginalSessionAnchor(
            listOf(10L, 99L, 88L), listOf(101L, 102L, 103L),
            listOf(1, 2, 3), 10L, 101L
        ))
        assertTrue(TrackMixNativeEnqueuePolicy.hasOriginalSessionAnchor(
            listOf(88L, 10L, 99L), listOf(103L, 101L, 102L),
            listOf(1, 2, 3), 10L, 101L
        ))
        assertFalse(TrackMixNativeEnqueuePolicy.hasOriginalSessionAnchor(
            listOf(88L), listOf(103L), listOf(1), 10L, 101L
        ))
        assertFalse(TrackMixNativeEnqueuePolicy.hasOriginalSessionAnchor(
            listOf(10L, 99L), listOf(101L, 102L), listOf(1, 9), 10L, 101L
        ))
    }

    @Test
    fun confirmsOnlyNewUniqueEnqueuedTrackEntry() {
        assertEquals(301L, TrackMixNativeEnqueuePolicy.newNativeEnqueueEntry(
            listOf(101L, 201L), listOf(101L, 201L, 202L, 301L),
            listOf(10L, 20L, 30L, 40L), 40L
        ))
        assertNull(TrackMixNativeEnqueuePolicy.newNativeEnqueueEntry(
            listOf(101L, 201L), listOf(101L, 201L, 301L, 302L),
            listOf(10L, 20L, 40L, 40L), 40L
        ))
    }

    @Test
    fun initialReserveFollowsGmmpSettings() {
        assertEquals(4, TrackMixNativeEnqueuePolicy.neededInitialFollowers(5, 1))
        assertEquals(3, TrackMixNativeEnqueuePolicy.neededInitialFollowers(2, 3))
        assertEquals(1, TrackMixNativeEnqueuePolicy.neededInitialFollowers(1, 0))
    }

    @Test
    fun nativeFilePredecodeGuardRequiresExactSinglePlayingSeed() {
        val policy = TrackMixNativeEnqueuePolicy
        assertTrue(policy.canPrimeFirstNextSource(
            listOf(10L), listOf(100L), listOf(1), 0, 1, 10L
        ))
        assertFalse(policy.canPrimeFirstNextSource(
            listOf(10L, 20L), listOf(100L, 200L), listOf(1, 2), 0, 1, 10L
        ))
        assertFalse(policy.canPrimeFirstNextSource(
            listOf(10L), listOf(100L), listOf(2), 0, 2, 10L
        ))
        assertFalse(policy.canPrimeFirstNextSource(
            listOf(11L), listOf(100L), listOf(1), 0, 1, 10L
        ))
        assertFalse(policy.canPrimeFirstNextSource(
            listOf(10L), listOf(100L), listOf(1), 0, null, 10L
        ))
    }

    @Test
    fun nativeContinuityFollowerMustBeExactlyOneVerifiedOriginalNativeEntry() {
        val policy = TrackMixNativeEnqueuePolicy
        assertEquals(101L, policy.verifiedFirstNextSourceEntry(
            listOf(10L, 20L), listOf(100L, 101L), listOf(1, 2), 0, 1, 10L, 100L
        ))
        assertNull(policy.verifiedFirstNextSourceEntry(
            listOf(10L, 20L), listOf(100L, 101L), listOf(1, 3), 0, 1, 10L, 100L
        ))
        assertNull(policy.verifiedFirstNextSourceEntry(
            listOf(10L, 20L), listOf(100L, 100L), listOf(1, 2), 0, 1, 10L, 100L
        ))
        assertNull(policy.verifiedFirstNextSourceEntry(
            listOf(11L, 20L), listOf(100L, 101L), listOf(1, 2), 0, 1, 10L, 100L
        ))
        assertNull(policy.verifiedFirstNextSourceEntry(
            listOf(10L, 20L), listOf(100L, 101L), listOf(1, 2), 1, 2, 10L, 100L
        ))
    }

    @Test
    fun nativeContinuityReservesOneSlotWithoutRemovingManualTracks() {
        assertEquals(3, TrackMixNativeEnqueuePolicy.recommendedFollowerCount(4, true))
        assertEquals(4, TrackMixNativeEnqueuePolicy.recommendedFollowerCount(4, false))
        assertEquals(0, TrackMixNativeEnqueuePolicy.recommendedFollowerCount(0, true))
        assertEquals(0, TrackMixNativeEnqueuePolicy.recommendedFollowerCount(1, true))
    }
}
