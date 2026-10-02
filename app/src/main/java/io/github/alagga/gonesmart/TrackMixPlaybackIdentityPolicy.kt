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
