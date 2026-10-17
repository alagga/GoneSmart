package io.github.alagga.gonesmart

internal object PlaylistBridgeRuleLabelPolicy {
    fun rewrite(
        original: String?,
        playlistLabel: String,
        smartPlaylistLabel: String,
        bridgeDisplayName: String?,
        nativeSmartDisplayName: String?
    ): String? {
        bridgeDisplayName?.takeUnless(String::isBlank)?.let {
            return "$playlistLabel: $it"
        }
        nativeSmartDisplayName?.takeUnless(String::isBlank)?.let {
            return "$smartPlaylistLabel: $it"
        }
        return original
    }
}
