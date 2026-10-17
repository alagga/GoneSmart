package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMixZeroPoolPolicyTest {
    @Test
    fun `accepted 421 next source callback is the exact tested passive shape`() {
        assertTrue(
            TrackMixZeroPoolPolicy.isAcceptedGmmp421NextSourceCommand(
                declaringClassName = "gonemad.gmmp.playback.service.MusicService",
                methodName = "I2",
                parameterTypeName = "int",
                returnTypeName = "void"
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.isAcceptedGmmp421NextSourceCommand(
                declaringClassName = "gonemad.gmmp.playback.service.MusicService",
                methodName = "I3",
                parameterTypeName = "int",
                returnTypeName = "void"
            )
        )
    }

    @Test
    fun `next source proof derives position from verified Current not opaque argument`() {
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = 2,
            currentQueuePosition = 2,
            writerReadback = 2
        )
        assertNotNull(boundary)
        assertEquals(2, boundary!!.currentQueuePosition)
        assertEquals(3, boundary.nextQueuePosition)
    }

    @Test
    fun `next source proof rejects identity or coordinate disagreement`() {
        assertNull(
            TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
                selectedTrackId = 18165L,
                currentTrackId = 18166L,
                selectedQueuePosition = 2,
                currentQueuePosition = 2,
                writerReadback = 2
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
                selectedTrackId = 18165L,
                currentTrackId = 18165L,
                selectedQueuePosition = 2,
                currentQueuePosition = 2,
                writerReadback = 1
            )
        )
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
    fun `r71 natural next source seed plan preserves absolute current anchor`() {
        val entries = (1..5).map { position ->
            TrackMixPlan.NativeQueueEntry(
                queueId = 100L + position,
                trackId = if (position == 2) 18165L else 20_000L + position,
                position = position
            )
        }
        val seed = TrackMixZeroPoolPolicy.seedPlan(
            entries = entries,
            currentEntryId = 102L,
            selectedTrackId = 18165L
        )
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = seed.currentPosition,
            currentQueuePosition = seed.currentPosition,
            writerReadback = seed.currentPosition
        )

        assertEquals(2, seed.currentPosition)
        assertEquals(listOf(101L), seed.pastEntryIds)
        assertEquals(listOf(103L, 104L, 105L), seed.futureEntryIds)
        assertNotNull(boundary)
        assertEquals(2, boundary!!.currentQueuePosition)
        assertEquals(3, boundary.nextQueuePosition)
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
    fun `prepared queue append gaps compact before native retarget prune`() {
        val gapped = listOf(
            TrackMixPlan.NativeQueueEntry(50, 6354, 1),
            TrackMixPlan.NativeQueueEntry(51, 7022, 7216),
            TrackMixPlan.NativeQueueEntry(52, 14339, 7217),
            TrackMixPlan.NativeQueueEntry(53, 6270, 7218),
            TrackMixPlan.NativeQueueEntry(54, 14338, 7219)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixPlan.planNativeRetarget(
                entries = gapped,
                currentPosition = 1,
                selectedTrackId = 6354,
                targetQueueSize = 5,
                smartTrackIds = emptyList()
            )
        }

        val rebase = TrackMixZeroPoolPolicy.rebasePlan(gapped, 50, 5)
        val compact = gapped.map { entry ->
            entry.copy(position = rebase.positionsByEntryId.getValue(entry.queueId))
        }
        val plan = TrackMixPlan.planNativeRetarget(
            entries = compact,
            currentPosition = 1,
            selectedTrackId = 6354,
            targetQueueSize = 5,
            smartTrackIds = emptyList()
        )
        assertEquals(listOf(6354L, 7022L, 14339L, 6270L, 14338L), plan.expectedTrackIds)
        assertEquals(emptyList<Long>(), plan.removeEntryIds)
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
    fun `natural source guard derives first follower from verified Current`() {
        val plan = TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
            currentQueuePosition = 6,
            nativeFallbackTrackIds = listOf(10106L, 653L)
        )
        assertEquals(7, plan.nextQueuePosition)
        assertEquals(10106L, plan.expectedTrackId)
    }

    @Test
    fun `natural source guard refuses invalid current or missing fallback identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 0,
                nativeFallbackTrackIds = listOf(10106L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 1,
                nativeFallbackTrackIds = emptyList()
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

    @Test
    fun `verified natural next source can bootstrap initial size when natural refill was absent`() {
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = 2,
            currentQueuePosition = 2,
            writerReadback = 2
        )
        assertNotNull(boundary)
        assertEquals(3, boundary!!.nextQueuePosition)

        val refill = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 0)
        assertNotNull(refill)
        assertEquals(4, refill?.topUpCount)
    }

    @Test
    fun `native refill boundary tops device observed three followers to initial five`() {
        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 3)
        assertNotNull(plan)
        assertEquals(1, plan?.topUpCount)
    }

    @Test
    fun `queue boundary requests all followers when no natural refill happened`() {
        val plan = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 0)
        assertNotNull(plan)
        assertEquals(4, plan?.topUpCount)
    }

    @Test
    fun `native refill boundary rejects overfill`() {
        assertNull(TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 5))
    }

    @Test
    fun `queue tail fallback accepts only exact zero-provider queue target before next source`() {
        assertTrue(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = true,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 3,
                queueSize = 5,
                currentQueuePosition = 4,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_file_browser",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
    }

}
