package io.github.alagga.gonesmart

import android.content.Context

/**
 * Text displayed INSIDE GMMP must come from its live host Resources.
 * English-only explanatory details belong in GoneSmart's own Logs, not
 * inside a foreign-language GMMP Toast. The installed GMMP 4.2.0 has
 * a native `error` resource; the symbol-only fallback is locale-neutral.
 */
internal object NativeGmmpUiText {
    fun string(context: Context, resourceName: String): String? {
        val id = context.resources.getIdentifier(
            resourceName, "string", context.packageName
        )
        if (id == 0) return null
        return runCatching { context.getString(id) }
            .getOrNull()?.takeUnless(String::isBlank)
    }

    fun errorLabel(nativeError: String?, nativeAction: String?): String {
        val error = nativeError?.takeUnless(String::isBlank) ?: "⚠"
        val action = nativeAction?.takeUnless(String::isBlank)
        return if (action == null) error else "$error · $action"
    }

    /**
     * Prefer the installed player's existing phrase if present. The
     * virtual folder is GoneSmart-owned, so its separate fully mapped
     * fallback follows the actual GMMP Activity language.
     */
    fun otherLocations(context: Context): String {
        val native = string(context, "other_locations")
            ?: string(context, "other_locations_title")
        if (native != null && !native.contains("%")) return native
        val locale = context.resources.configuration.locales[0]
        return GoneSmartGmmpStrings.otherLocations(locale)
    }

    /**
     * Smart DJ is GoneSmart-owned. GMMP has native Auto-DJ and rating
     * nouns, but no translations for our technical recommendation states.
     * Preserve the full English explanation in the companion Logs while
     * host-player notices use only installed GMMP words and neutral symbols.
     */
    fun smartDjNoticeLabel(
        notice: String,
        nativeAutoDj: String?,
        nativeRating: String?,
        nativeError: String?
    ): String {
        val dj = nativeAutoDj?.takeUnless(String::isBlank) ?: "Auto-DJ"
        return when (notice) {
            "cache-preparing" -> "GoneSmart ⏳"
            "cache-ready" -> "GoneSmart ✓"
            "offline-native-fallback", "no-matches-native-fallback" -> "$dj ↩"
            "no-seeds" -> errorLabel(nativeError, dj)
            "no-matches-stopped" -> "$dj ⏹"
            "rating-fallback" -> (nativeRating?.takeUnless(String::isBlank)
                ?: dj) + " ↩"
            else -> errorLabel(nativeError, dj)
        }
    }

    fun smartDjNotice(context: Context, notice: String): String =
        smartDjNoticeLabel(
            notice,
            string(context, "auto_dj"),
            string(context, "rating"),
            string(context, "error")
        )

    /**
     * GMMP 4.2.0 exposes `smart_playlists` only in plural form. The
     * Playlist Bridge editor needs a singular discriminator beside the
     * already-native `playlist` label. Keep the verified German/English
     * wording exact; for other host locales retain GMMP's own localized
     * Smart-Playlists noun rather than leaking an English translation.
     */
    internal fun smartPlaylistLabel(
        language: String,
        nativeSmartPlaylists: String?
    ): String {
        val normalized = when (language.lowercase(java.util.Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "no" -> "nb"
            else -> language.lowercase(java.util.Locale.ROOT)
        }
        return when (normalized) {
            "de", "en" -> "Smart Playlist"
            else -> nativeSmartPlaylists
                ?.takeUnless(String::isBlank)
                ?: "Smart Playlist"
        }
    }

    fun smartPlaylist(context: Context): String =
        smartPlaylistLabel(
            context.resources.configuration.locales[0].language,
            string(context, "smart_playlists")
        )

    /**
     * Build the native Smart-Playlist chooser title from GMMP vocabulary.
     * German/English are exact on the tested device. In other locales,
     * replace GMMP's own localized singular playlist noun inside its
     * localized `link_playlist` phrase when that is structurally safe.
     * If the noun is inflected differently, fall back to the localized
     * Smart-Playlists noun instead of fabricating grammar.
     */
    internal fun linkSmartPlaylistLabel(
        language: String,
        nativeLinkPlaylist: String?,
        nativePlaylist: String?,
        smartPlaylist: String
    ): String {
        val normalized = when (language.lowercase(java.util.Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "no" -> "nb"
            else -> language.lowercase(java.util.Locale.ROOT)
        }
        if (normalized == "de") return "Smart Playlist verlinken"
        if (normalized == "en") return "Link Smart Playlist"

        val link = nativeLinkPlaylist?.takeUnless(String::isBlank)
        val playlist = nativePlaylist?.takeUnless(String::isBlank)
        if (link != null && playlist != null) {
            val index = link.indexOf(playlist, ignoreCase = true)
            if (index >= 0) {
                return link.replaceRange(
                    index,
                    index + playlist.length,
                    smartPlaylist
                )
            }
        }
        return smartPlaylist
    }

    fun linkSmartPlaylist(context: Context): String {
        val smart = smartPlaylist(context)
        return linkSmartPlaylistLabel(
            context.resources.configuration.locales[0].language,
            string(context, "link_playlist"),
            string(context, "playlist"),
            smart
        )
    }

    fun error(context: Context, nativeAction: String? = null): String =
        errorLabel(string(context, "error"), nativeAction)
}
