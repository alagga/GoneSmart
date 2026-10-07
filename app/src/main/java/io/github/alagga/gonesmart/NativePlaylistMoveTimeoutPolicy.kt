package io.github.alagga.gonesmart

/**
 * Native py0.b opens a REAL confirmation. The user can leave that dialog
 * open for any length of time; only native worker time AFTER dismissal may
 * count toward a cancellation or scan timeout.
 */
internal object NativePlaylistMoveTimeoutPolicy {
    private const val DISMISS_GRACE_MS = 60_000L

    fun isAbandoned(
        dismissedAtMs: Long?,
        nowMs: Long,
        originalsStillPresent: Boolean
    ): Boolean = dismissedAtMs != null &&
        nowMs >= dismissedAtMs &&
        nowMs - dismissedAtMs >= DISMISS_GRACE_MS &&
        originalsStillPresent

    fun hasTimedOut(
        polls: Int,
        waitingForUserConfirmation: Boolean
    ): Boolean = polls >= 360 && !waitingForUserConfirmation
}
