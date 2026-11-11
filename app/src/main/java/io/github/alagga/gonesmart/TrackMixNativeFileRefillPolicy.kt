package io.github.alagga.gonesmart

/** Ownership/postcondition checks for PLAY_FILE followed by GMMP's native qr.z. */
internal object TrackMixNativeFileRefillPolicy {
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

    fun neededInitialFollowers(initialSize: Int, upcomingCount: Int): Int =
        maxOf((initialSize - 1).coerceAtLeast(0), upcomingCount.coerceAtLeast(1))

    /**
     * A native append may be shorter than requested, but every new Track ID
     * must come from the prepared smart pool and all earlier/manual queue
     * entries must retain their identity, order and Current ownership.
     */
    fun verifiedSmartRefill(
        beforeTrackIds: List<Long>,
        beforeEntryIds: List<Long>,
        beforePositions: List<Int>,
        afterTrackIds: List<Long>,
        afterEntryIds: List<Long>,
        afterPositions: List<Int>,
        currentBefore: Long?,
        currentAfter: Long?,
        requested: Int,
        preparedTrackIds: List<Long>
    ): List<Long>? {
        if (requested <= 0 || currentBefore == null ||
            beforeTrackIds.size != beforeEntryIds.size ||
            beforePositions.size != beforeEntryIds.size ||
            afterTrackIds.size != afterEntryIds.size ||
            afterPositions.size != afterEntryIds.size ||
            preparedTrackIds.isEmpty() ||
            preparedTrackIds.distinct().size != preparedTrackIds.size
        ) return null

        val beforeRows = beforeEntryIds.indices.map { index ->
            Triple(beforeEntryIds[index], beforeTrackIds[index], beforePositions[index])
        }.sortedBy { it.third }
        val afterRows = afterEntryIds.indices.map { index ->
            Triple(afterEntryIds[index], afterTrackIds[index], afterPositions[index])
        }.sortedBy { it.third }
        if (!AutoDjQueueAppendPolicy.confirmed(
                before = beforeRows.map { it.first to it.third },
                after = afterRows.map { it.first to it.third },
                currentEntryId = currentBefore,
                afterCurrentEntryId = currentAfter,
                requested = requested
            )
        ) return null

        val priorIds = beforeEntryIds.toSet()
        val preservedRows = afterRows.filter { it.first in priorIds }
        if (preservedRows.map { it.first to it.second } !=
            beforeRows.map { it.first to it.second }
        ) return null

        val addedTrackIds = afterRows.filter { it.first !in priorIds }.map { it.second }
        if (addedTrackIds.distinct().size != addedTrackIds.size ||
            addedTrackIds.any { it in beforeTrackIds || it !in preparedTrackIds }
        ) return null
        return addedTrackIds
    }
}
