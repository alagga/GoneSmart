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
    fun `natural current fallback accepts absolute and zero based state coordinates`() {
        assertEquals(
            1,
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = 1,
                selectedQueuePosition = 1
            )
        )
        assertEquals(
            5,
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = 4,
                selectedQueuePosition = 5
            )
        )
    }

    @Test
    fun `natural current fallback rejects wider or invalid coordinate guesses`() {
        assertNull(
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = 3,
                selectedQueuePosition = 5
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = -1,
                selectedQueuePosition = 1
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.resolveNaturalCurrentBoundaryPosition(
                observedArgument = 1,
                selectedQueuePosition = 0
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
    fun `late source rebuild restores exactly the predecode native fallback identities`() {
        assertEquals(
            listOf(19118L, 12703L, 1987L, 17034L, 6272L),
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = 19118L,
                nativeFallbackTrackIds = listOf(12703L, 1987L, 17034L, 6272L),
                expectedSize = 5
            )
        )
    }

    @Test
    fun `natural source guard uses only the persisted first native fallback identity`() {
        val plan = TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
            currentQueuePosition = 1,
            observedArgument = 2,
            nativeFallbackTrackIds = listOf(10106L, 653L, 11874L, 15245L)
        )
        assertEquals(2, plan.nextQueuePosition)
        assertEquals(10106L, plan.expectedTrackId)
    }

    @Test
    fun `natural source guard refuses anything except rebased current plus one`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 1,
                observedArgument = 3,
                nativeFallbackTrackIds = listOf(10106L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 6,
                observedArgument = 7,
                nativeFallbackTrackIds = listOf(10106L)
            )
        }
    }

    @Test
    fun `late source rebuild fails closed when the predecode fallback identity is incomplete`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.expectedRestoredTrackIds(
                selectedTrackId = 19118L,
                nativeFallbackTrackIds = listOf(12703L, 1987L, 17034L),
                expectedSize = 5
            )
        }
    }

    @Test
    fun `late source batch carries exact native fallback identities after rebuilt current`() {
        val entries = (1..12).map { position ->
            TrackMixZeroPoolPolicy.SourceBatchEntry(
                trackId = if (position == 6) 19118L else 30_000L + position,
                position = position
            )
        }
        val plan = TrackMixZeroPoolPolicy.sourceBatchGuardPlan(
            entries = entries,
            selectedTrackId = 19118L,
            nativeFallbackTrackIds = listOf(1400L, 3778L, 6105L, 15523L),
            expectedSize = 5
        )
        assertNotNull(plan)
        assertEquals(6, plan!!.currentPosition)
        assertEquals(
            linkedMapOf(7 to 1400L, 8 to 3778L, 9 to 6105L, 10 to 15523L),
            plan.trackIdsByPosition
        )
    }

    @Test
    fun `source batch guard ignores initial size and fails closed on ambiguous source`() {
        assertNull(
            TrackMixZeroPoolPolicy.sourceBatchGuardPlan(
                entries = (1..5).map { position ->
                    TrackMixZeroPoolPolicy.SourceBatchEntry(
                        trackId = if (position == 1) 19118L else 100L + position,
                        position = position
                    )
                },
                selectedTrackId = 19118L,
                nativeFallbackTrackIds = listOf(1400L, 3778L, 6105L, 15523L),
                expectedSize = 5
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.sourceBatchGuardPlan(
                entries = listOf(
                    TrackMixZeroPoolPolicy.SourceBatchEntry(19118L, 6),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(19118L, 7),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(99L, 8),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(98L, 9),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(97L, 10),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(96L, 11)
                ),
                selectedTrackId = 19118L,
                nativeFallbackTrackIds = listOf(1400L, 3778L, 6105L, 15523L),
                expectedSize = 5
            )
        )
    }

    @Test
    fun `source batch guard requires all contiguous upcoming source positions`() {
        assertNull(
            TrackMixZeroPoolPolicy.sourceBatchGuardPlan(
                entries = listOf(
                    TrackMixZeroPoolPolicy.SourceBatchEntry(19118L, 6),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(1L, 7),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(2L, 8),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(3L, 10),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(4L, 11),
                    TrackMixZeroPoolPolicy.SourceBatchEntry(5L, 12)
                ),
                selectedTrackId = 19118L,
                nativeFallbackTrackIds = listOf(1400L, 3778L, 6105L, 15523L),
                expectedSize = 5
            )
        )
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
