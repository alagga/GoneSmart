package io.github.alagga.gonesmart

internal object TrackMixZeroPoolPolicy {
    data class PositionCommandCandidate(
        val key: String,
        val argument: Int,
        val ageMs: Long
    )

    data class BoundarySelection(
        val key: String?,
        val matchCount: Int
    )

    data class SeedPlan(
        val currentEntryId: Long,
        val currentPosition: Int,
        val pastEntryIds: List<Long>,
        val futureEntryIds: List<Long>
    )

    data class RebasePlan(
        val positionsByEntryId: Map<Long, Int>,
        val currentPosition: Int
    )

    fun selectNextSourceBoundary(
        currentQueuePosition: Int,
        candidates: List<PositionCommandCandidate>,
        maxAgeMs: Long = 3_000L
    ): BoundarySelection {
        if (currentQueuePosition <= 0 ||
            currentQueuePosition == Int.MAX_VALUE ||
            maxAgeMs < 0L
        ) {
            return BoundarySelection(null, 0)
        }
        val expected = currentQueuePosition + 1
        val matches = candidates
            .filter { candidate ->
                candidate.argument == expected &&
                    candidate.ageMs in 0..maxAgeMs
            }
            .distinctBy { it.key }
        return BoundarySelection(
            key = matches.singleOrNull()?.key,
            matchCount = matches.size
        )
    }

    data class StateWriterMapping(
        val argumentOffset: Int,
        val signalOffset: Int
    )

    fun resolveStateWriterMapping(
        currentQueuePosition: Int,
        observedArgument: Int,
        signalValue: Int,
        ageMs: Long,
        maxAgeMs: Long = 10_000L
    ): StateWriterMapping? {
        if (currentQueuePosition <= 0 || ageMs !in 0..maxAgeMs) return null
        val argumentOffset = currentQueuePosition - observedArgument
        val signalOffset = currentQueuePosition - signalValue
        if (argumentOffset !in 0..1 || signalOffset !in 0..1) return null
        return StateWriterMapping(
            argumentOffset = argumentOffset,
            signalOffset = signalOffset
        )
    }

    fun seedPlan(
        entries: List<TrackMixPlan.NativeQueueEntry>,
        currentEntryId: Long,
        selectedTrackId: Long
    ): SeedPlan {
        require(entries.isNotEmpty()) { "Native queue is empty" }
        require(entries.map { it.queueId }.distinct().size == entries.size) {
            "Native queue IDs are not unique"
        }
        require(entries.map { it.position }.distinct().size == entries.size) {
            "Native queue positions are not unique"
        }
        val current = entries.singleOrNull { it.queueId == currentEntryId }
            ?: error("Pinned native Current entry is unavailable")
        require(current.trackId == selectedTrackId) {
            "Pinned native Current changed before zero-pool isolation"
        }
        require(current.position > 0) {
            "Pinned native Current position must be positive"
        }
        return SeedPlan(
            currentEntryId = current.queueId,
            currentPosition = current.position,
            pastEntryIds = entries
                .filter { it.position < current.position }
                .sortedBy { it.position }
                .map { it.queueId },
            futureEntryIds = entries
                .filter { it.position > current.position }
                .sortedBy { it.position }
                .map { it.queueId }
        )
    }

    /**
     * R48 identity contract for a late Smart-source rebuild. The fallback IDs
     * are not newly selected here: they must be the exact native GMMP IDs that
     * were already present in the decoder-ready predecode Initial Size queue.
     */
    fun expectedRestoredTrackIds(
        selectedTrackId: Long,
        nativeFallbackTrackIds: List<Long>,
        expectedSize: Int
    ): List<Long> {
        require(selectedTrackId > 0L) { "Selected Track ID must be positive" }
        require(expectedSize > 0) { "Expected queue size must be positive" }
        require(nativeFallbackTrackIds.size == expectedSize - 1) {
            "Native fallback identity count does not match Initial Size"
        }
        require(nativeFallbackTrackIds.all { it > 0L }) {
            "Native fallback Track IDs must be positive"
        }
        return listOf(selectedTrackId) + nativeFallbackTrackIds
    }

    data class NaturalNextSourceGuardPlan(
        val nextQueuePosition: Int,
        val expectedTrackId: Long
    )

    /**
     * R50 pre-source contract. The only guarded source boundary is the unique
     * natural CURRENT+1 command, and its Track identity must come from the
     * already persisted native fallback order selected before decode.
     */
    fun naturalNextSourceGuardPlan(
        currentQueuePosition: Int,
        observedArgument: Int,
        nativeFallbackTrackIds: List<Long>
    ): NaturalNextSourceGuardPlan {
        require(currentQueuePosition == 1) {
            "Zero-pool Current must be rebased to queue position 1"
        }
        require(observedArgument == currentQueuePosition + 1) {
            "Observed source command is not CURRENT+1"
        }
        require(nativeFallbackTrackIds.isNotEmpty()) {
            "Zero-pool native fallback has no current+1 Track"
        }
        require(nativeFallbackTrackIds.all { it > 0L }) {
            "Zero-pool native fallback Track IDs must be positive"
        }
        return NaturalNextSourceGuardPlan(
            nextQueuePosition = observedArgument,
            expectedTrackId = nativeFallbackTrackIds.first()
        )
    }

    fun rebasePlan(
        entries: List<TrackMixPlan.NativeQueueEntry>,
        currentEntryId: Long,
        expectedSize: Int
    ): RebasePlan {
        require(expectedSize > 0) { "Expected queue size must be positive" }
        require(entries.size == expectedSize) {
            "Native queue size does not match Initial Size"
        }
        require(entries.map { it.queueId }.distinct().size == entries.size) {
            "Native queue IDs are not unique"
        }
        require(entries.map { it.position }.distinct().size == entries.size) {
            "Native queue positions are not unique"
        }
        val ordered = entries.sortedBy { it.position }
        require(ordered.first().queueId == currentEntryId) {
            "Zero-pool Current is not the first native queue row"
        }
        return RebasePlan(
            positionsByEntryId = ordered.mapIndexed { index, entry ->
                entry.queueId to (index + 1)
            }.toMap(LinkedHashMap()),
            currentPosition = 1
        )
    }
}
