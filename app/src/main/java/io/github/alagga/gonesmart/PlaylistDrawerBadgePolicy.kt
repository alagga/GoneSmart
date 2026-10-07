package io.github.alagga.gonesmart

import java.util.Locale

/**
 * Match only GMMP's real Playlist / Smart-Playlist drawer entries. Titles
 * come from the installed player's localized resources; no translated text is
 * guessed by GoneSmart.
 */
internal object PlaylistDrawerBadgePolicy {
    private fun normalized(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    fun matchesExactLocalizedTitle(
        localizedNativeNames: List<String>,
        actualNativeTitle: String
    ): Boolean {
        val normalizedTitle = normalized(actualNativeTitle)
        return normalizedTitle.isNotEmpty() &&
            localizedNativeNames.any {
                normalized(it) == normalizedTitle
            }
    }

    fun matchesNativePlaylist(
        localizedNativeNames: List<String>,
        nativeMenuEntryName: String,
        actualNativeTitle: String
    ): Boolean {
        val id = nativeMenuEntryName.lowercase(Locale.ROOT)
        val title = actualNativeTitle.trim()
        val normalizedTitle = normalized(title)
        val smartLike = id.contains("smart") ||
            title.contains("smart", ignoreCase = true)
        return !smartLike && (
            localizedNativeNames.any {
                normalized(it) == normalizedTitle
            } || (id.contains("playlist") && !id.contains("smart"))
        )
    }

    fun matchesNativeSmartPlaylist(
        localizedNativeNames: List<String>,
        nativeMenuEntryName: String,
        actualNativeTitle: String
    ): Boolean {
        val id = nativeMenuEntryName.lowercase(Locale.ROOT)
        val title = actualNativeTitle.trim()
        val normalizedTitle = normalized(title)
        return id.contains("smart") ||
            title.contains("smart", ignoreCase = true) ||
            localizedNativeNames.any {
                normalized(it) == normalizedTitle
            }
    }
}
