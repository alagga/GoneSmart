package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QueueFlipPlannerTest {
    @Test fun ordinaryPlaylistStartsWithItsOriginalLastSong() {
        val plan = QueueFlipPlanner.reverseForNewPlayback(
            listOf("A", "B", "C", "D", "E")
        )
        assertEquals(listOf("E", "D", "C", "B", "A"), plan.entries)
        assertEquals(0, plan.newCurrentIndex)
        assertEquals("E", plan.entries[plan.newCurrentIndex])
    }

    @Test fun smartPlaylistReversesResolvedOrderNotTheOldQueue() {
        val gmmpResolvedSmartPlaylist = listOf("One", "Two", "Three")
        val plan = QueueFlipPlanner.reverseForNewPlayback(
            gmmpResolvedSmartPlaylist
        )
        assertEquals(listOf("Three", "Two", "One"), plan.entries)
        assertEquals("Three", plan.entries[plan.newCurrentIndex])
        assertEquals(
            listOf("One", "Two", "Three"),
            gmmpResolvedSmartPlaylist
        )
    }

    @Test fun newlyPlayedEmptyPlaylistHasNoCurrentSong() {
        val plan = QueueFlipPlanner.reverseForNewPlayback(emptyList<String>())
        assertEquals(emptyList<String>(), plan.entries)
        assertEquals(-1, plan.newCurrentIndex)
    }

    @Test fun reversesWholeQueueWhileKeepingCurrentSongPlaying() {
        val original = listOf("A", "B", "C", "D", "E")
        val plan = QueueFlipPlanner.reverseAll(original, currentIndex = 1)

        assertEquals(listOf("E", "D", "C", "B", "A"), plan.entries)
        assertEquals(3, plan.newCurrentIndex)
        assertEquals("B", plan.entries[plan.newCurrentIndex])
        assertEquals(listOf("A", "B", "C", "D", "E"), original)
    }

    @Test fun midpointCurrentHappensToStayAtTheSameIndex() {
        val plan = QueueFlipPlanner.reverseAll(
            listOf("A", "B", "C", "D", "E"),
            currentIndex = 2
        )
        assertEquals(listOf("E", "D", "C", "B", "A"), plan.entries)
        assertEquals(2, plan.newCurrentIndex)
    }

    @Test fun reversesBothPastAndUpcomingWithoutExceptions() {
        val plan = QueueFlipPlanner.reverseAll(
            listOf("A", "B", "C", "D", "E", "F"),
            currentIndex = 4
        )
        assertEquals(listOf("F", "E", "D", "C", "B", "A"), plan.entries)
        assertEquals(1, plan.newCurrentIndex)
        assertEquals("E", plan.entries[plan.newCurrentIndex])
    }

    @Test fun preservesCurrentEntryIdentityWhenSongIdsRepeat() {
        data class Entry(val trackId: Long, val uniqueId: Long)

        val a = Entry(4L, 10L)
        val current = Entry(4L, 11L)
        val b = Entry(7L, 12L)
        val c = Entry(4L, 13L)

        val plan = QueueFlipPlanner.reverseAll(
            listOf(a, current, b, c),
            currentIndex = 1
        )
        assertEquals(listOf(c, b, current, a), plan.entries)
        assertEquals(2, plan.newCurrentIndex)
        assertSame(current, plan.entries[plan.newCurrentIndex])
    }

    @Test fun doubleFlipRestoresOriginalOrderAndCurrentPosition() {
        val original = listOf("A", "B", "C", "D")
        val first = QueueFlipPlanner.reverseAll(original, currentIndex = 1)
        val second = QueueFlipPlanner.reverseAll(
            first.entries,
            currentIndex = first.newCurrentIndex
        )
        assertEquals(original, second.entries)
        assertEquals(1, second.newCurrentIndex)
    }

    @Test fun handlesEmptySingleAndInvalidQueues() {
        assertEquals(
            QueueFlipPlanner.Plan(emptyList<String>(), -1),
            QueueFlipPlanner.reverseAll(emptyList<String>(), -1)
        )
        assertEquals(
            QueueFlipPlanner.Plan(listOf("A"), 0),
            QueueFlipPlanner.reverseAll(listOf("A"), 0)
        )
        assertThrows(IllegalArgumentException::class.java) {
            QueueFlipPlanner.reverseAll(listOf("A", "B"), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QueueFlipPlanner.reverseAll(listOf("A", "B"), 2)
        }
    }
}
