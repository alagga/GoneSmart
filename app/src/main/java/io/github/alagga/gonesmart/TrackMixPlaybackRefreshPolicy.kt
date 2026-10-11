package io.github.alagga.gonesmart

/**
 * A controlled same-position playback refresh is allowed only after Track Mix
 * has rebuilt a non-empty upcoming queue around the still-selected Current
 * track. It must never be used to repair an ambiguous/current-changed queue.
 */
internal object TrackMixPlaybackRefreshPolicy {
    fun canRefresh(
        selectedTrackId: Long,
        currentTrackId: Long?,
        queueSize: Int,
        currentPosition: Int?
    ): Boolean =
        selectedTrackId > 0L &&
            currentTrackId == selectedTrackId &&
            queueSize >= 2 &&
            currentPosition != null &&
            currentPosition > 0
}
