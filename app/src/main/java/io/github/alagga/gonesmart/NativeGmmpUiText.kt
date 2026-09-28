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
     * GMMP 4.2.0 has no standalone singular smart-playlist string. It does
     * have `smart_playlist_editor` and `smart_playlists`. Derive the
     * singular only where the native editor title itself exposes a safe,
     * exact boundary; otherwise keep GMMP's own plural noun.
     */
    internal fun smartPlaylistLabel(
        language: String,
        nativeEditor: String?,
        nativePlural: String?
    ): String {
        val editor = nativeEditor?.takeUnless(String::isBlank)
        val normalized = when (language.lowercase(java.util.Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "no" -> "nb"
            else -> language.lowercase(java.util.Locale.ROOT)
        }
        if (normalized == "de" && editor?.endsWith("-Editor") == true) {
            return editor.removeSuffix("-Editor")
        }
        if (normalized == "en" && editor?.endsWith(" Editor") == true) {
            return editor
                .removeSuffix(" Editor")
                .replace("Smart Playlist", "Smart-Playlist")
        }

        // Keep GMMP's own localized noun whenever no safe singular can be
        // derived. If that native wording literally uses the English token
        // pair "Smart Playlist", normalize only the maintainer-requested
        // punctuation and leave every other translated word untouched.
        val native = nativePlural
            ?.takeUnless(String::isBlank)
            ?: editor
        if (native != null) {
            return native.replace("Smart Playlist", "Smart-Playlist")
        }
        return "Smart-Playlist"
    }

    fun smartPlaylist(context: Context): String =
        smartPlaylistLabel(
            context.resources.configuration.locales[0].language,
            string(context, "smart_playlist_editor"),
            string(context, "smart_playlists")
        )

    /**
     * Compose the linked-Smart chooser title exclusively from the host's
     * `link_playlist` and `playlist` wording plus the native-derived
     * Smart-Playlist noun. This preserves the player's verb/order when its
     * localized singular playlist noun occurs verbatim.
     */
    internal fun linkSmartPlaylistLabel(
        nativeLinkPlaylist: String?,
        nativePlaylist: String?,
        smartPlaylist: String
    ): String {
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
            string(context, "link_playlist"),
            string(context, "playlist"),
            smart
        )
    }

    fun error(context: Context, nativeAction: String? = null): String =
        errorLabel(string(context, "error"), nativeAction)
}
