package io.github.alagga.gonesmart

/**
 * Match ONLY GMMP's real Playlist drawer entry. Titles are taken from the
 * actual installed player's localized resources; no English/German guess.
 */
internal object PlaylistDrawerBadgePolicy {
    fun matchesNativePlaylist(
        localizedNativeNames: List<String>,
        nativeMenuEntryName: String,
        actualNativeTitle: String
    ): Boolean =
        localizedNativeNames.any {
            it.equals(actualNativeTitle.trim(), ignoreCase = true)
        } || nativeMenuEntryName.contains("playlist", ignoreCase = true)
}
