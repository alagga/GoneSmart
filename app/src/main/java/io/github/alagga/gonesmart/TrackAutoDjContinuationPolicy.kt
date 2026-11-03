package io.github.alagga.gonesmart

/**
 * After a managed 4.2.1 Track Auto-DJ has started playback, a verified
 * natural CURRENT transition may request GMMP's exact upcoming-track
 * deficit only when the actual last queue row becomes CURRENT.
 *
 * Playback never waits on provider networking and no placeholder is added.
 */
internal object TrackAutoDjContinuationPolicy {
    fun refillCount(
        queueSize: Int,
        currentIndex: Int,
        upcomingTrackCount: Int
    ): Int {
        if (queueSize <= 0 || currentIndex != queueSize - 1) return 0
        return upcomingTrackCount.coerceAtLeast(1)
    }
}
