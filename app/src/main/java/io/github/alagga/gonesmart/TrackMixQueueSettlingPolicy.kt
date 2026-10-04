package io.github.alagga.gonesmart

/**
 * Bounds the hand-off between GMMP's native Play action and Track Mix queue
 * isolation. Some list-backed Play actions populate queue_table asynchronously:
 * the current track can already be correct while thousands of later rows are
 * still being appended. A short quiet period alone is not sufficient because
 * Smart Playlist expansion can pause for ~1s before replacing the temporary
 * queue image. Track Mix therefore adds a completion guard for the generic
 * track-list menu used by Smart Playlists, while ordinary playlist-detail
 * playback keeps the normal short quiet-window path.
 */
internal object TrackMixQueueSettlingPolicy {
    const val QUIET_WINDOW_MS = 700L
    const val COMPLETION_GUARD_MS = 2_500L
    const val WAIT_TIMEOUT_MS = 20_000L

    fun requiredCompletionGuardMs(source: String): Long =
        if (source == "menu_gm_context_track") COMPLETION_GUARD_MS else 0L

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

    fun selectedTargetStillUsable(
        selectedTrackOccurrences: Int,
        currentStillSelected: Boolean,
        detectedQueueSize: Int,
        currentQueueSize: Int
    ): Boolean =
        selectedTrackOccurrences == 1 &&
            (
                currentStillSelected ||
                    currentQueueSize != detectedQueueSize
            )

    fun isSettled(
        sameQueue: Boolean,
        stableForMs: Long,
        sinceDetectionMs: Long,
        requiredGuardMs: Long
    ): Boolean =
        sameQueue &&
            stableForMs >= QUIET_WINDOW_MS &&
            sinceDetectionMs >= requiredGuardMs.coerceAtLeast(0L)
}
