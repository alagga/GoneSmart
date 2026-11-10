package io.github.alagga.gonesmart

/** Conservative player Next control candidates from live GMMP resource names. */
internal object DeferredAutoDjNextPolicy {
    private val supportedNames = setOf(
        "next", "nexttrack", "nextsong", "nextbutton", "nexttrackbutton",
        "btnnext", "btnnexttrack", "buttonnext", "buttonnexttrack",
        "playernext", "playernextbutton", "playernexttrack",
        "playbacknext", "transportnext", "actionnext", "skipnext"
    )
    // Repeated taps during a slow provider search are usually impatience,
    // not an intentional request to skip several future songs.
    const val MAX_PENDING_SKIPS = 1

    fun isNativeNextButton(resourceEntryName: String?): Boolean {
        if (resourceEntryName.isNullOrBlank()) return false
        return resourceEntryName.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' } in supportedNames
    }

    fun increment(current: Int): Int = (current + 1).coerceIn(0, MAX_PENDING_SKIPS)

    /** Native Next must have reached GMMP's verified end-of-queue callback. */
    fun shouldRememberNativeNext(
        commandReachedQueueEnd: Boolean,
        hadNoUpcomingAtRequest: Boolean,
        nativeRefillStarted: Boolean
    ): Boolean = commandReachedQueueEnd && hadNoUpcomingAtRequest && !nativeRefillStarted

    fun hasNativeQueueAddition(
        beforeNativeEntryIds: Set<Long>,
        currentEntryIds: Set<Long>
    ): Boolean = currentEntryIds.any { it !in beforeNativeEntryIds }

    /** A native refill may complete after GMMP has already ended playback. */
    fun shouldResumeEndedQueue(
        hadNoUpcoming: Boolean,
        audioInactiveAtRequest: Boolean,
        audioInactiveAfterRefill: Boolean,
        originalCurrentEntryId: Long?,
        liveCurrentEntryId: Long?,
        pendingManualNextPresses: Int
    ): Boolean = hadNoUpcoming && audioInactiveAtRequest &&
        audioInactiveAfterRefill && pendingManualNextPresses == 0 &&
        originalCurrentEntryId != null &&
        originalCurrentEntryId == liveCurrentEntryId

    fun canReplay(
        initialSessionId: Long,
        liveSessionId: Long,
        originalEntryIds: Set<Long>,
        liveEntryIds: Set<Long>,
        hasNext: Boolean,
        enabled: Boolean
    ): Boolean = enabled && hasNext && initialSessionId == liveSessionId &&
        originalEntryIds.any { it in liveEntryIds }
}
