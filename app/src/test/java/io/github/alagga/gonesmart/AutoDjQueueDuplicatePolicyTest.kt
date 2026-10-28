package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDjQueueDuplicatePolicyTest {
    private fun item(entry: Long, track: Long, position: Int) = QueueItemInfo(
        orderIndex = position - 1,
        queuePosition = position,
        shufflePosition = position,
        queueEntryId = entry,
        track = TrackInfo(track, "t$track", "artist", null, null),
        state = if (position == 1) QueueItemState.CURRENT else QueueItemState.UPCOMING
    )

    private fun queue(vararg items: QueueItemInfo) = QueueContext(1, items.toList())

    @Test fun detectsNewEntryRepeatingCurrentOrAnotherNewEntry() {
        val before = queue(item(10, 99, 1))
        val after = queue(item(10, 99, 1), item(11, 99, 2), item(12, 42, 3), item(13, 42, 4))
        assertEquals(setOf(99L, 42L), AutoDjQueueDuplicatePolicy.newDuplicateTrackIds(before, after))
    }

    @Test fun unchangedManualDuplicatesAndNormalNewTracksAreNotFlagged() {
        val before = queue(item(10, 99, 1), item(11, 99, 2))
        val after = queue(item(10, 99, 1), item(11, 99, 2), item(12, 42, 3))
        assertEquals(emptySet<Long>(), AutoDjQueueDuplicatePolicy.newDuplicateTrackIds(before, after))
    }
}
