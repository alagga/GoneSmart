package io.github.alagga.gonesmart

/**
 * Match only GMMP's real Playlist / Smart-Playlist drawer entries. Titles
 * come from the installed player's localized resources; no translated text is
 * guessed by GoneSmart.
 */
internal object PlaylistDrawerBadgePolicy {
    fun matchesNativePlaylist(
        localizedNativeNames: List<String>,
        nativeMenuEntryName: String,
        actualNativeTitle: String
    ): Boolean {
        val id = nativeMenuEntryName.lowercase()
        val smartId = id.contains("smart") && id.contains("playlist")
        return !smartId && (
            localizedNativeNames.any {
                it.equals(actualNativeTitle.trim(), ignoreCase = true)
            } || (id.contains("playlist") && !id.contains("smart"))
        )
    }

    fun matchesNativeSmartPlaylist(
        localizedNativeNames: List<String>,
        nativeMenuEntryName: String,
        actualNativeTitle: String
    ): Boolean {
        val id = nativeMenuEntryName.lowercase()
        return localizedNativeNames.any {
            it.equals(actualNativeTitle.trim(), ignoreCase = true)
        } || (id.contains("smart") && id.contains("playlist"))
    }
}
