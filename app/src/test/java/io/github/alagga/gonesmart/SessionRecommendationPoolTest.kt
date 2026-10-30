package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecommendationPoolTest {

    private val sizing =
        SmartPoolSizing(
            targetSize = 20,
            lowWaterMark = 6,
            minimumConsumedBeforeRefill = 2,
            rescueCandidateTarget = 12
        )

    @Test
    fun scarceSuccessfulFillDoesNotImmediatelyTriggerAnotherTopUp() {
        val pool = SessionRecommendationPool()
        pool.reset(newSessionId = 7L, newTargetSize = 20)

        val added = pool.mergeCandidates(
            expectedSessionId = 7L,
            candidateTrackIds = listOf(1L, 2L, 3L, 4L, 5L, 6L),
            newTargetSize = 20
        )
        pool.recordRefillResult(expectedSessionId = 7L, addedCount = added)

        pool.commitSelected(
            expectedSessionId = 7L,
            selectedTrackIds = listOf(1L, 2L, 3L, 4L)
        )

        assertFalse(pool.shouldRefill(expectedSessionId = 7L, sizing = sizing))
    }

    @Test
    fun scarceSuccessfulFillKeepsBackgroundTopUpSuppressedAfterConsumption() {
        val pool = SessionRecommendationPool()
        pool.reset(newSessionId = 8L, newTargetSize = 20)

        val candidates = listOf(11L, 12L, 13L, 14L, 15L, 16L)
        val added = pool.mergeCandidates(
            expectedSessionId = 8L,
            candidateTrackIds = candidates,
            newTargetSize = 20
        )
        pool.recordRefillResult(expectedSessionId = 8L, addedCount = added)
        pool.commitSelected(expectedSessionId = 8L, selectedTrackIds = candidates)

        assertFalse(pool.shouldRefill(expectedSessionId = 8L, sizing = sizing))
    }

    @Test
    fun preparedRecommendationsCarryIntoVerifiedNativeFileSession() {
        val pool = SessionRecommendationPool()
        pool.reset(newSessionId = -100L, newTargetSize = 20)
        pool.mergeCandidates(-100L, listOf(11L, 22L, 33L), 20)
        assertFalse(pool.rebindPreparedSession(-99L, 9L, 20))
        assertTrue(pool.rebindPreparedSession(-100L, 9L, 20))
        assertFalse(pool.isForSession(-100L))
        assertEquals(listOf(11L, 22L, 33L), pool.peek(9L, 3, emptySet()))
        pool.commitSelected(9L, listOf(11L, 22L))
        assertEquals(listOf(33L), pool.peek(9L, 3, emptySet()))
    }

    @Test
    fun manualQueueEntriesSurviveNativeFileSessionAdoptionAndCommit() {
        fun queueItem(index: Int, entryId: Long, trackId: Long) = QueueItemInfo(
            orderIndex = index,
            queuePosition = index + 1,
            shufflePosition = index + 1,
            queueEntryId = entryId,
            track = TrackInfo(trackId, "Track $trackId", "Artist", null, "/music/$trackId.mp3"),
            state = if (index == 0) QueueItemState.CURRENT else QueueItemState.UPCOMING
        )

        val seed = queueItem(0, 101L, 1L)
        val manual = queueItem(1, 102L, 9L)
        val recommended = queueItem(2, 103L, 11L)
        val before = QueueContext(1, listOf(seed, manual), "verified-native-writer-hint")
        val after = QueueContext(1, listOf(seed, manual, recommended), "verified-native-writer-hint")

        val pool = SessionRecommendationPool()
        pool.reset(newSessionId = -101L, newTargetSize = 20)
        pool.mergeCandidates(-101L, listOf(11L, 22L), 20)

        val tracker = QueueSessionTracker()
        val session = tracker.forceNewSession(before, "native-file Track Mix seed")
        assertTrue(pool.rebindPreparedSession(-101L, session.sessionId, 20))
        assertEquals(listOf(11L, 22L), pool.peek(session.sessionId, 2, setOf(1L, 9L)))

        val additions = tracker.commitGeneratedAdditions(before, after, QueueEntryOrigin.GONESMART)
        pool.commitSelected(session.sessionId, additions.map { it.track.id })

        assertEquals(listOf(103L), additions.map { it.queueEntryId })
        assertEquals(QueueEntryOrigin.USER_QUEUE, tracker.originOf(manual.queueEntryId))
        assertEquals(QueueEntryOrigin.GONESMART, tracker.originOf(recommended.queueEntryId))
        assertEquals(listOf(22L), pool.peek(session.sessionId, 2, emptySet()))
    }
}
