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
}
