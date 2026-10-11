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
        val text = original ?: return null
        val prefix = "$playlistLabel:"
        if (text.startsWith(prefix, ignoreCase = true)) {
            val suffix = text.substring(prefix.length).trimStart()
            if (suffix.isNotBlank()) return "$smartPlaylistLabel: $suffix"
        }
        return original
    }
}
