package io.github.alagga.gonesmart

/** Pure queue ownership checks for the experimental native file-command route. */
internal object TrackMixNativeEnqueuePolicy {
    fun canStart(legacyQueue: Boolean, selectedPath: String?, initialSize: Int): Boolean =
        !legacyQueue && !selectedPath.isNullOrBlank() &&
            selectedPath.startsWith('/') && initialSize > 0

    fun hasOriginalSessionAnchor(
        actualTrackIds: List<Long>,
        actualEntryIds: List<Long>,
        actualQueuePositions: List<Int>,
        originalTrackId: Long,
        originalEntryId: Long
    ): Boolean = actualTrackIds.size == actualEntryIds.size &&
        actualQueuePositions == (1..actualTrackIds.size).toList() &&
        actualEntryIds.distinct().size == actualEntryIds.size &&
        actualEntryIds.indices.any { index ->
            actualEntryIds[index] == originalEntryId &&
                actualTrackIds[index] == originalTrackId
        }

    fun newNativeEnqueueEntry(
        beforeEntryIds: List<Long>,
        afterEntryIds: List<Long>,
        afterTrackIds: List<Long>,
        selectedTrackId: Long
    ): Long? {
        if (afterEntryIds.size != afterTrackIds.size) return null
        val previous = beforeEntryIds.toSet()
        val matches = afterEntryIds.indices.filter { index ->
            afterEntryIds[index] !in previous &&
                afterTrackIds[index] == selectedTrackId
        }
        return matches.singleOrNull()?.let { afterEntryIds[it] }
    }

    fun neededInitialFollowers(initialSize: Int, upcomingCount: Int): Int =
        maxOf((initialSize - 1).coerceAtLeast(0), upcomingCount.coerceAtLeast(1))
}
