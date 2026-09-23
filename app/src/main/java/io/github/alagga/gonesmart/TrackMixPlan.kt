package io.github.alagga.gonesmart

/**
 * Native GMMP's Initial Size includes the selected seed song.
 * Never request an extra recommendation when the requested total is
 * already met, and never treat a negative/empty queue as complete.
 */
internal object TrackMixPlan {
    fun additionalTracksNeeded(initialSize: Int, actualQueueSize: Int): Int {
        require(initialSize > 0) { "Initial queue size must be positive" }
        require(actualQueueSize >= 0) { "Queue size must not be negative" }
        return (initialSize - actualQueueSize).coerceAtLeast(0)
    }

    fun hasEnoughTracks(initialSize: Int, actualQueueSize: Int): Boolean =
        additionalTracksNeeded(initialSize, actualQueueSize) == 0

    /**
     * A new native track menu must always put the action immediately
     * after "Play next" without reordering any other native items.
     */
    fun insertionIndex(playNextIndex: Int, itemCount: Int): Int {
        require(playNextIndex in 0 until itemCount)
        return playNextIndex + 1
    }
}
