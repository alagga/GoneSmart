package io.github.alagga.gonesmart

/**
 * Bounds the hand-off between GMMP's native Play action and Track Mix queue
 * isolation. Some list-backed Play actions populate queue_table asynchronously:
 * the current track can already be correct while thousands of later rows are
 * still being appended. Track Mix may isolate only after that native queue
 * image has been unchanged for a short, bounded quiet window.
 */
internal object TrackMixQueueSettlingPolicy {
    const val QUIET_WINDOW_MS = 700L
    const val WAIT_TIMEOUT_MS = 12_000L

    fun sameQueue(
        previousTrackIds: List<Long>,
        previousEntryIds: List<Long>,
        previousCurrentIndex: Int,
        currentTrackIds: List<Long>,
        currentEntryIds: List<Long>,
        currentCurrentIndex: Int
    ): Boolean =
        previousCurrentIndex == currentCurrentIndex &&
            previousEntryIds == currentEntryIds &&
            previousTrackIds == currentTrackIds

    fun isSettled(
        sameQueue: Boolean,
        stableForMs: Long
    ): Boolean = sameQueue && stableForMs >= QUIET_WINDOW_MS
}
