package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackMixZeroPoolPolicyTest {
    @Test
    fun `promotes only the unique recent current plus one position command`() {
        val result = TrackMixZeroPoolPolicy.selectNextSourceBoundary(
            currentQueuePosition = 1,
            candidates = listOf(
                TrackMixZeroPoolPolicy.PositionCommandCandidate("other", 9, 100),
                TrackMixZeroPoolPolicy.PositionCommandCandidate("next", 2, 900)
            )
        )
        assertEquals("next", result.key)
        assertEquals(1, result.matchCount)
    }

    @Test
    fun `ambiguous or stale next source boundaries fail closed`() {
        val ambiguous = TrackMixZeroPoolPolicy.selectNextSourceBoundary(
            6,
            listOf(
                TrackMixZeroPoolPolicy.PositionCommandCandidate("a", 7, 100),
                TrackMixZeroPoolPolicy.PositionCommandCandidate("b", 7, 200)
            )
        )
        assertNull(ambiguous.key)
        assertEquals(2, ambiguous.matchCount)

        val stale = TrackMixZeroPoolPolicy.selectNextSourceBoundary(
            1,
            listOf(
                TrackMixZeroPoolPolicy.PositionCommandCandidate("next", 2, 3_001)
            )
        )
        assertNull(stale.key)
        assertEquals(0, stale.matchCount)
    }

    @Test
    fun `track mix state writer maps zero based argument and signal to queue position`() {
        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(
            currentQueuePosition = 5,
            observedArgument = 4,
            signalValue = 4,
            ageMs = 500
        )
        assertNotNull(mapping)
        assertEquals(1, mapping!!.argumentOffset)
        assertEquals(1, mapping.signalOffset)
    }

    @Test
    fun `track mix state writer allows mixed absolute signal and zero based argument`() {
        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(
            currentQueuePosition = 5,
            observedArgument = 4,
            signalValue = 5,
            ageMs = 500
        )
        assertNotNull(mapping)
        assertEquals(1, mapping!!.argumentOffset)
        assertEquals(0, mapping.signalOffset)
    }

    @Test
    fun `track mix state writer refuses stale or wider coordinate guesses`() {
        assertNull(
            TrackMixZeroPoolPolicy.resolveStateWriterMapping(
                currentQueuePosition = 5,
                observedArgument = 3,
                signalValue = 4,
                ageMs = 500
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.resolveStateWriterMapping(
                currentQueuePosition = 5,
                observedArgument = 4,
                signalValue = 4,
                ageMs = 10_001
            )
        )
    }

    @Test
    fun `seed plan drops history and source followers around the exact pinned row`() {
        val entries = (1..8).map { position ->
            TrackMixPlan.NativeQueueEntry(
                queueId = 100L + position,
                trackId = 1_000L + position,
                position = position
            )
        }
        val plan = TrackMixZeroPoolPolicy.seedPlan(
            entries = entries,
            currentEntryId = 106L,
            selectedTrackId = 1_006L
        )
        assertEquals(6, plan.currentPosition)
        assertEquals(listOf(101L, 102L, 103L, 104L, 105L), plan.pastEntryIds)
        assertEquals(listOf(107L, 108L), plan.futureEntryIds)
    }

    @Test
    fun `initial native refill can be compacted to one through Initial Size`() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(20, 200, 1),
            TrackMixPlan.NativeQueueEntry(21, 201, 71),
            TrackMixPlan.NativeQueueEntry(22, 202, 72)
        )
        val plan = TrackMixZeroPoolPolicy.rebasePlan(entries, 20, 3)
        assertEquals(mapOf(20L to 1, 21L to 2, 22L to 3), plan.positionsByEntryId)
        assertEquals(1, plan.currentPosition)
    }

    @Test
    fun `rebase refuses a queue where the pinned current is not first`() {
        val entries = listOf(
            TrackMixPlan.NativeQueueEntry(20, 200, 1),
            TrackMixPlan.NativeQueueEntry(21, 201, 2)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.rebasePlan(entries, 21, 2)
        }
    }
}
