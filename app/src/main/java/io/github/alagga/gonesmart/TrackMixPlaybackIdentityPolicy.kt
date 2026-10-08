package io.github.alagga.gonesmart

/**
 * Track Auto-DJ waits for GMMP's selected/current entry, not for the rest of
 * the queue to stop changing. GMMP 4.2.1 can keep asynchronously reshaping
 * upcoming/history rows after native Play has already switched the current
 * entry.
 */
internal object TrackMixPlaybackIdentityPolicy {
    data class Identity(
        val queueEntryId: Long?,
        val trackId: Long,
        val currentIndex: Int
    )

    fun changed(before: Identity?, current: Identity): Boolean =
        before == null ||
            current.queueEntryId != before.queueEntryId ||
            current.trackId != before.trackId ||
            current.currentIndex != before.currentIndex

    /**
     * Native Play is complete for Track Auto-DJ once GMMP's actual current
     * track/queue entry changes. A mere currentIndex shift can happen while
     * 4.2.1 reshapes history/upcoming rows and is not playback evidence.
     */
    fun playbackChanged(before: Identity?, current: Identity): Boolean {
        if (before == null) return true
        if (current.trackId != before.trackId) return true
        return before.queueEntryId != null &&
            current.queueEntryId != null &&
            current.queueEntryId != before.queueEntryId
    }

    fun acceptSameCurrentQueuePlay(
        source: String,
        nativePlayAccepted: Boolean,
        nativePlaySignal: Boolean,
        before: Identity?,
        current: Identity,
        stableMs: Long,
        actionAgeMs: Long
    ): Boolean =
        source == "menu_gm_context_queue" &&
            nativePlayAccepted &&
            nativePlaySignal &&
            before != null &&
            sameCurrent(before, current) &&
            stableMs >= 350L &&
            actionAgeMs >= 1_500L

    fun sameCurrent(first: Identity?, second: Identity): Boolean {
        if (first == null) return false
        return if (first.queueEntryId != null && second.queueEntryId != null) {
            first.queueEntryId == second.queueEntryId &&
                first.trackId == second.trackId
        } else {
            first.trackId == second.trackId
        }
    }
}
