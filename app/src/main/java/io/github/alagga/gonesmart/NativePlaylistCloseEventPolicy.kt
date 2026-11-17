package io.github.alagga.gonesmart

/**
 * A single GMMP picker close for one user-initiated multi-destination add.
 * The native event class is verified at the call site; this pure policy
 * rejects expired, single-destination and exhausted batches.
 */
internal object NativePlaylistCloseEventPolicy {
    fun eligible(
        eventMatches: Boolean,
        requestedDestinations: Int,
        acceptedDestinations: Int,
        eventsObserved: Int,
        beforeDeadline: Boolean
    ): Boolean =
        eventMatches && beforeDeadline &&
            requestedDestinations > 1 &&
            acceptedDestinations > 1 &&
            eventsObserved >= 0 &&
            eventsObserved < acceptedDestinations
}
