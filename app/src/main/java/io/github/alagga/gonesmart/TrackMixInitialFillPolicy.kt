package io.github.alagga.gonesmart

/**
 * Track Mix must reach GMMP's configured Initial Size without racing an
 * already-running native Auto-DJ refill. GMMP can legitimately request only
 * its normal Upcoming count after the isolated seed (for example 1) even
 * when Initial Size is larger (for example 5).
 */
internal object TrackMixInitialFillPolicy {
    fun expectedQueueSizeAfterObservedRefill(
        initialSize: Int,
        seedQueueSize: Int,
        observedRequestedTracks: Int
    ): Int {
        require(initialSize > 0) { "Initial queue size must be positive" }
        require(seedQueueSize >= 0) { "Seed queue size must not be negative" }
        require(observedRequestedTracks >= 0) {
            "Observed refill size must not be negative"
        }
        return minOf(
            initialSize,
            seedQueueSize + observedRequestedTracks
        )
    }

    /**
     * Returns null while an observed native refill has not materialized yet.
     * This prevents Track Mix from issuing a second qr.z(count) concurrently.
     * Once the observed request is visible in the verified queue, return only
     * the remaining number of tracks needed to reach Initial Size.
     */
    fun safeSupplementCount(
        initialSize: Int,
        seedQueueSize: Int,
        observedRequestedTracks: Int,
        actualQueueSize: Int
    ): Int? {
        require(actualQueueSize >= 0) { "Queue size must not be negative" }
        val expectedAfterObserved = expectedQueueSizeAfterObservedRefill(
            initialSize = initialSize,
            seedQueueSize = seedQueueSize,
            observedRequestedTracks = observedRequestedTracks
        )
        if (observedRequestedTracks > 0 &&
            actualQueueSize < expectedAfterObserved
        ) {
            return null
        }
        return TrackMixPlan.additionalTracksNeeded(
            initialSize = initialSize,
            actualQueueSize = actualQueueSize
        )
    }
}
