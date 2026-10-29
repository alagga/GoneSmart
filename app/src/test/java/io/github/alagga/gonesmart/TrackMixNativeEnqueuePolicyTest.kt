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
}
