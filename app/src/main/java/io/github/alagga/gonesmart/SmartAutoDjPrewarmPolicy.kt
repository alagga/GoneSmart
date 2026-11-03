package io.github.alagga.gonesmart

/**
 * Starts GoneSmart recommendation preparation on a verified natural
 * Current-position event, before GMMP's next native Auto-DJ refill request.
 * This authorizes a background READ/search only, never native track insertion.
 */
internal object SmartAutoDjPrewarmPolicy {
    fun shouldPrewarm(
        enabled: Boolean,
        currentPosition: Int?,
        trackMixPending: Boolean,
        managedTrackMix: Boolean
    ): Boolean = enabled && currentPosition != null && currentPosition > 0 &&
        !trackMixPending && !managedTrackMix
}
