package io.github.alagga.gonesmart

/**
 * Let native GMMP maintain audio continuity when its last queue entry ends
 * before the asynchronous recommendation pool is ready.
 *
 * No synthetic tracks, seeks, direct queue writes or playback restarts.
 */
internal object AutoDjColdTailContinuityPolicy {
    fun useNativeImmediately(
        poolReady: Boolean,
        upcomingCount: Int,
        nativeFallbackAllowed: Boolean,
        managedTrackMix: Boolean
    ): Boolean = !poolReady && upcomingCount == 0 &&
        nativeFallbackAllowed && !managedTrackMix
}
