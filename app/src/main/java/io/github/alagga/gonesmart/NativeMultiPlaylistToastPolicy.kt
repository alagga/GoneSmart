package io.github.alagga.gonesmart

/**
 * One transaction-scoped identity tag for each native GMMP success Toast.
 * Match the installed GMMP locale's exact add_to_playlist_toast result, not
 * generic numbers, substrings or an arbitrary Toast emitted nearby.
 */
internal object NativeMultiPlaylistToastPolicy {
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
