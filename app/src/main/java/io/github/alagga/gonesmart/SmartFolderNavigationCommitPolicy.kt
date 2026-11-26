package io.github.alagga.gonesmart

/**
 * Retain the previously complete Smart folder surface during background IO.
 * The new projection is committed only once a full native model snapshot is
 * available; no raw native row is exposed before native holders and the
 * scroll position have independently settled.
 */
internal object SmartFolderNavigationCommitPolicy {
    fun shouldMaskAtCommit(
        previousContentReady: Boolean,
        navigationPending: Boolean,
        nativeListChanged: Boolean
    ): Boolean = previousContentReady &&
        (navigationPending || nativeListChanged)

    fun mayRevealAtDraw(
        isFront: Boolean,
        nativeContentReady: Boolean,
        scrollSettled: Boolean,
        scrollResetPending: Boolean
    ): Boolean = isFront && !nativeContentReady &&
        scrollSettled && !scrollResetPending
}
