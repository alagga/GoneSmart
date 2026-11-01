package io.github.alagga.gonesmart

/**
 * Native Auto-DJ tends to refill at the audio end boundary, when its player
 * may already have committed to an absent next source. A verified prior
 * Auto-DJ session may refill one track sooner without changing Current.
 */
internal object AutoDjWarmQueuePolicy {
    fun refillCount(
        queueSize: Int,
        currentIndex: Int,
        configuredUpcoming: Int
    ): Int {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) return 0
        val remaining = queueSize - currentIndex - 1
        val safeUpcoming = maxOf(2, configuredUpcoming.coerceAtLeast(1))
        return (safeUpcoming - remaining).coerceAtLeast(0)
            .coerceAtMost(safeUpcoming)
    }
}
