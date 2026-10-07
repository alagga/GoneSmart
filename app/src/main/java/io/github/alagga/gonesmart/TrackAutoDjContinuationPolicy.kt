package io.github.alagga.gonesmart

/**
 * Pure continuation policy for the 4.2.1 Track Auto-DJ queue-root repair.
 *
 * GMMP's hidden append/end allocator is intentionally not guessed. Once a
 * Track Auto-DJ-owned queue has been normalized to 1..N, a verified natural
 * CURRENT transition may request only the deficit needed to restore GMMP's
 * own configured upcoming-track count through the already-proven qr.z path.
 */
internal object TrackAutoDjContinuationPolicy {
    fun refillCount(
        queueSize: Int,
        currentIndex: Int,
        upcomingTrackCount: Int
    ): Int {
        if (queueSize <= 0) return 0
        if (currentIndex !in 0 until queueSize) return 0

        val targetUpcoming = upcomingTrackCount.coerceAtLeast(1)
        val remaining = queueSize - currentIndex - 1
        return (targetUpcoming - remaining).coerceAtLeast(0)
    }
}
