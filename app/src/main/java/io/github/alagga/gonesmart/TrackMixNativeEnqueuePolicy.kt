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

    /** Only an exact one-row native PLAY_FILE queue can be primed before I2. */
    fun canPrimeFirstNextSource(
        ids: List<Long>,
        entryIds: List<Long>,
        positions: List<Int>,
        currentIndex: Int,
        currentPosition: Int?,
        selectedTrackId: Long
    ): Boolean = ids == listOf(selectedTrackId) &&
        entryIds.size == 1 && entryIds.single() > 0L &&
        positions == listOf(1) && currentIndex == 0 && currentPosition == 1

    /** The native qr.z(1) must preserve CURRENT and add exactly one next slot. */
    fun verifiedFirstNextSourceEntry(
        ids: List<Long>,
        entryIds: List<Long>,
        positions: List<Int>,
        currentIndex: Int,
        currentPosition: Int?,
        selectedTrackId: Long,
        selectedEntryId: Long
    ): Long? {
        if (ids.size != 2 || entryIds.size != 2 ||
            positions != listOf(1, 2) || currentIndex != 0 ||
            currentPosition != 1 || ids[0] != selectedTrackId ||
            entryIds[0] != selectedEntryId ||
            entryIds[1] <= 0L || entryIds[1] == selectedEntryId ||
            ids[1] <= 0L
        ) return null
        return entryIds[1]
    }

    /** Reserve space for the already-decoded native continuity follower. */
    fun recommendedFollowerCount(requested: Int, nativeContinuityPresent: Boolean): Int =
        (requested - if (nativeContinuityPresent) 1 else 0).coerceAtLeast(0)
}
