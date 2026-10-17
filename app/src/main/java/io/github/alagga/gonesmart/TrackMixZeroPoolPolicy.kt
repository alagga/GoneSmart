package io.github.alagga.gonesmart

internal object TrackMixZeroPoolPolicy {
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

    data class VerifiedNextSourceBoundary(
        val currentQueuePosition: Int,
        val nextQueuePosition: Int
    )

    /**
     * Accepted GMMP 4.2.1 event alias. The name/signature is allowed to
     * identify a passive next-source callback only because repeated device
     * traces correlate the natural I2 invocation with the immediately nested
     * queue_position lookup. Mutation authority still comes exclusively from
     * resolveVerifiedNextSourceBoundary below.
     */
    fun isAcceptedGmmp421NextSourceCommand(
        declaringClassName: String,
        methodName: String,
        parameterTypeName: String?,
        returnTypeName: String?
    ): Boolean =
        declaringClassName == "gonemad.gmmp.playback.service.MusicService" &&
            methodName == "I2" &&
            parameterTypeName == "int" &&
            returnTypeName == "void"

    /**
     * R70 proof for a natural next-source event. The obfuscated method
     * argument is deliberately absent: the selected row, independently
     * resolved Current, and verified writer/readback must all agree on one
     * positive absolute queue_position. Next is derived from that coordinate.
     */
    fun resolveVerifiedNextSourceBoundary(
        selectedTrackId: Long,
        currentTrackId: Long?,
        selectedQueuePosition: Int,
        currentQueuePosition: Int?,
        writerReadback: Int?
    ): VerifiedNextSourceBoundary? {
        if (selectedTrackId <= 0L || currentTrackId != selectedTrackId) return null
        val current = currentQueuePosition ?: return null
        if (current <= 0 || current == Int.MAX_VALUE) return null
        if (selectedQueuePosition != current || writerReadback != current) return null
        return VerifiedNextSourceBoundary(
            currentQueuePosition = current,
            nextQueuePosition = current + 1
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

    fun resolveNaturalCurrentBoundaryPosition(
        observedArgument: Int,
        selectedQueuePosition: Int
    ): Int? {
        if (observedArgument < 0 || selectedQueuePosition <= 0) return null
        val offset = selectedQueuePosition - observedArgument
        if (offset !in 0..1) return null
        return selectedQueuePosition
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

    data class NativeRefillBoundaryPlan(
        val topUpCount: Int
    )

    fun nativeRefillBoundaryPlan(
        initialSize: Int,
        originalRequested: Int
    ): NativeRefillBoundaryPlan? {
        if (initialSize <= 1 || originalRequested < 0) return null
        val requiredFollowers = initialSize - 1
        if (originalRequested > requiredFollowers) return null
        return NativeRefillBoundaryPlan(
            topUpCount = requiredFollowers - originalRequested
        )
    }

    data class NaturalNextSourceGuardPlan(
        val nextQueuePosition: Int,
        val expectedTrackId: Long
    )

    /**
     * R70 pre-source contract. The next queue position is always derived from
     * the independently verified Current coordinate, never from I2(int)'s
     * opaque argument.
     */
    fun naturalNextSourceGuardPlan(
        currentQueuePosition: Int,
        nativeFallbackTrackIds: List<Long>
    ): NaturalNextSourceGuardPlan {
        require(currentQueuePosition > 0 && currentQueuePosition < Int.MAX_VALUE) {
            "Zero-pool Current queue position must be positive and incrementable"
        }
        require(nativeFallbackTrackIds.isNotEmpty()) {
            "Zero-pool native fallback has no current+1 Track"
        }
        require(nativeFallbackTrackIds.all { it > 0L }) {
            "Zero-pool native fallback Track IDs must be positive"
        }
        return NaturalNextSourceGuardPlan(
            nextQueuePosition = currentQueuePosition + 1,
            expectedTrackId = nativeFallbackTrackIds.first()
        )
    }

    /**
     * If Queue Play selects the last native row, GMMP has no existing follower
     * that can force the natural next-source callback before Track Mix's worker
     * verifies the selection. R70 allows the already accepted position-1
     * zero-pool native fill only for that exact tail case and only if no
     * next-source event or other zero-pool boundary has already occurred.
     */
    fun shouldPrepareQueueTailFallback(
        source: String,
        currentResolutionSource: String?,
        preparedTrackCount: Int,
        boundaryPrepared: Boolean,
        boundaryAttempted: Boolean,
        nextSourceObserved: Boolean,
        currentIndex: Int,
        queueSize: Int,
        currentQueuePosition: Int?,
        currentTrackId: Long?,
        selectedTrackId: Long
    ): Boolean {
        if (source != "menu_gm_context_queue") return false
        if (currentResolutionSource != "queue-context-target") return false
        if (preparedTrackCount != 0 || boundaryPrepared || boundaryAttempted) return false
        if (nextSourceObserved) return false
        if (selectedTrackId <= 0L || currentTrackId != selectedTrackId) return false
        if (queueSize <= 0 || currentIndex != queueSize - 1) return false
        return currentQueuePosition != null && currentQueuePosition > 0
    }

    data class SourceBatchEntry(
        val trackId: Long,
        val position: Int
    )

    data class SourceBatchGuardPlan(
        val currentPosition: Int,
        val trackIdsByPosition: Map<Int, Long>
    )

    /**
     * R51 late-source-rebuild contract. GMMP may materialize the original
     * Smart-Playlist source after the first CURRENT+1 decoder was already
     * prepared correctly. Preserve the exact native fallback identities in
     * that new source batch before its queue rows become visible to the
     * internal next-source lookup. No queue ID/order/position is changed.
     */
    fun sourceBatchGuardPlan(
        entries: List<SourceBatchEntry>,
        selectedTrackId: Long,
        nativeFallbackTrackIds: List<Long>,
        expectedSize: Int
    ): SourceBatchGuardPlan? {
        val expectedTrackIds = expectedRestoredTrackIds(
            selectedTrackId = selectedTrackId,
            nativeFallbackTrackIds = nativeFallbackTrackIds,
            expectedSize = expectedSize
        )
        if (entries.size <= expectedTrackIds.size) return null
        if (entries.any { it.trackId <= 0L || it.position <= 0 }) return null
        if (entries.map { it.position }.distinct().size != entries.size) return null

        val current = entries.singleOrNull { it.trackId == selectedTrackId }
            ?: return null
        if (current.position > Int.MAX_VALUE - nativeFallbackTrackIds.size) {
            return null
        }
        val byPosition = entries.associateBy { it.position }
        val rewrites = linkedMapOf<Int, Long>()
        nativeFallbackTrackIds.forEachIndexed { index, trackId ->
            val position = current.position + index + 1
            if (byPosition[position] == null) return null
            rewrites[position] = trackId
        }
        return SourceBatchGuardPlan(
            currentPosition = current.position,
            trackIdsByPosition = rewrites
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
