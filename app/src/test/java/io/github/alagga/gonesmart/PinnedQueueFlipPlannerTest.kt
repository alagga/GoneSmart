package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedQueueFlipPlannerTest {
    @Test fun flipsEntireQueueWithCurrentInMiddle() {
        val original = listOf("A", "B", "C", "D", "E")
        val result = PinnedQueueFlipPlanner.flipPinned(original, 2)

        assertEquals(listOf("E", "D", "C", "B", "A"), result)
        assertEquals(original, listOf("A", "B", "C", "D", "E"))
        assertEquals("C", result[2])
    }

    @Test fun pinsCurrentEvenWhenCurrentIsNotInMiddle() {
        val result = PinnedQueueFlipPlanner.flipPinned(
            listOf("A", "B", "C", "D", "E"),
            1
        )
        assertEquals(listOf("E", "B", "D", "C", "A"), result)
    }

    @Test fun reversesAllOtherTracksNotTwoSeparateSegments() {
        val result = PinnedQueueFlipPlanner.flipPinned(
            listOf("A", "B", "C", "D", "E", "F"),
            4
        )
        assertEquals(listOf("F", "D", "C", "B", "E", "A"), result)
    }

    @Test fun duplicateTrackIdsDoNotConfusePinnedEntry() {
        data class Entry(val trackId: Long, val uniqueId: Long)

        val a = Entry(4L, 10L)
        val current = Entry(4L, 11L)
        val b = Entry(7L, 12L)
        val c = Entry(4L, 13L)

        val result = PinnedQueueFlipPlanner.flipPinned(
            listOf(a, current, b, c),
            1
        )
        assertEquals(listOf(c, current, b, a), result)
        assertSame(current, result[1])
    }

    @Test fun acceptsEmptyAndSingleTrackQueues() {
        assertEquals(
            emptyList<String>(),
            PinnedQueueFlipPlanner.flipPinned(emptyList(), -1)
        )
        assertEquals(
            listOf("A"),
            PinnedQueueFlipPlanner.flipPinned(listOf("A"), 0)
        )
    }

    @Test fun rejectsInvalidCurrentPosition() {
        assertThrows(IllegalArgumentException::class.java) {
            PinnedQueueFlipPlanner.flipPinned(listOf("A", "B"), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PinnedQueueFlipPlanner.flipPinned(listOf("A", "B"), 2)
        }
    }
}
