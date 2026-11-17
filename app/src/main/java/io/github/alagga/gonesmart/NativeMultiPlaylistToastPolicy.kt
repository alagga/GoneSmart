package io.github.alagga.gonesmart

/**
 * One transaction-scoped identity tag for each native GMMP success Toast.
 * Match the installed GMMP locale's exact add_to_playlist_toast result, not
 * generic numbers, substrings or an arbitrary Toast emitted nearby.
 */
internal object NativeMultiPlaylistToastPolicy {
    /**
     * In GMMP 4.2.1 the source List is no longer reachable through the old
     * picker handler. The real success Toast is independently observable,
     * and its localized count gives read-only postcondition evidence.
     *
     * A format that maps different file counts to the same text is rejected.
     * The scan runs only for a user-triggered multi-add, not during playback.
     */
    fun resolveNativeCount(
        actualMessage: String,
        knownCount: Int,
        localizedMessage: (Int) -> String?,
        maxCount: Int = 256
    ): Int? {
        if (actualMessage.isBlank()) return null
        if (knownCount > 0) {
            return knownCount.takeIf {
                localizedMessage(it) == actualMessage
            }
        }
        var match: Int? = null
        for (candidate in 1..maxCount) {
            if (localizedMessage(candidate) != actualMessage) continue
            if (match != null) return null
            match = candidate
        }
        return match
    }

    fun shouldTag(
        actualMessage: String,
        nativeMessage: String,
        sourceCount: Int,
        requestedDestinations: Int,
        taggedCount: Int,
        nowMs: Long,
        expiresAtMs: Long
    ): Boolean =
        sourceCount > 0 &&
            requestedDestinations > 1 &&
            taggedCount in 0 until requestedDestinations &&
            nowMs <= expiresAtMs &&
            nativeMessage.isNotBlank() &&
            actualMessage == nativeMessage
}
