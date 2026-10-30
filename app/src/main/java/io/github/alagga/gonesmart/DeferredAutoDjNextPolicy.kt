package io.github.alagga.gonesmart

/** Conservative player Next control candidates from live GMMP resource names. */
internal object DeferredAutoDjNextPolicy {
    private val supportedNames = setOf(
        "next", "nexttrack", "nextsong", "nextbutton", "nexttrackbutton",
        "btnnext", "btnnexttrack", "buttonnext", "buttonnexttrack",
        "playernext", "playernextbutton", "playernexttrack",
        "playbacknext", "transportnext", "actionnext", "skipnext"
    )
    const val MAX_PENDING_SKIPS = 4

    fun isNativeNextButton(resourceEntryName: String?): Boolean {
        if (resourceEntryName.isNullOrBlank()) return false
        return resourceEntryName.lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' } in supportedNames
    }

    fun increment(current: Int): Int = (current + 1).coerceIn(0, MAX_PENDING_SKIPS)

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
