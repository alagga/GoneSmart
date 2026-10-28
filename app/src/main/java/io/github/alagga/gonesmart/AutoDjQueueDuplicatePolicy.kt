package io.github.alagga.gonesmart

/**
 * Reports a duplicate only if a new GMMP queue entry repeats a Track ID that
 * is also present in the post-refill queue. Existing user-made repeats alone
 * are not attributed to the current Auto-DJ refill.
 */
internal object AutoDjQueueDuplicatePolicy {
    fun newDuplicateTrackIds(before: QueueContext, after: QueueContext): Set<Long> {
        val oldEntries = before.items.mapTo(hashSetOf()) { it.queueEntryId }
        val counts = after.items.groupingBy { it.track.id }.eachCount()
        return after.items.asSequence()
            .filter { it.queueEntryId !in oldEntries }
            .map { it.track.id }
            .filter { it > 0L && (counts[it] ?: 0) > 1 }
            .toSet()
    }
}
