package io.github.alagga.gonesmart

/**
 * Re-evaluate a cached null CURRENT+1 source only after GMMP has appended a
 * real next queue entry. This never authorizes a wait in a native transport
 * callback, a guessed playback command or a second recommendation request.
 */
internal object AutoDjNextSourceRefreshPolicy {
    fun shouldRefresh(
        hadNoUpcomingAtRequest: Boolean,
        nativeNullSourceObserved: Boolean,
        nativeSourceAlreadyRecovered: Boolean,
        nativeAppendVerified: Boolean,
        originalCurrentEntryId: Long,
        liveCurrentEntryId: Long?,
        liveUpcomingCount: Int,
        audioActive: Boolean?,
        pendingManualNextPresses: Int,
        sourceCallbackAvailable: Boolean,
        acceptedGmmpVersion: Boolean
    ): Boolean =
        hadNoUpcomingAtRequest &&
            nativeNullSourceObserved &&
            !nativeSourceAlreadyRecovered &&
            nativeAppendVerified &&
            originalCurrentEntryId > 0L &&
            originalCurrentEntryId == liveCurrentEntryId &&
            liveUpcomingCount > 0 &&
            audioActive == true &&
            pendingManualNextPresses == 0 &&
            sourceCallbackAvailable &&
            acceptedGmmpVersion
}
