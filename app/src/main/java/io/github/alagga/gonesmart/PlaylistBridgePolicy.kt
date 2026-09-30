package io.github.alagga.gonesmart

import java.io.File
import java.util.Locale

/**
 * Privacy and classification helpers shared by Playlist Bridge and
 * Smart-Playlist move safety. Paths are reduced to extension/hash/length
 * before they reach diagnostic Logcat output.
 */
internal object PlaylistBridgePolicy {
    fun isNativeSmartPlaylistReference(value: String?): Boolean {
        if (PlaylistBridgeReference.isBridgeValue(value)) return false
        if (value.isNullOrBlank()) return false
        val separator = value.indexOf('|')
        if (separator <= 0) return false
        val path = value.substring(0, separator).trim()
        return path.endsWith(".spl", ignoreCase = true)
    }

    fun safeReference(value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val separator = value.indexOf('|')
        val path = if (separator >= 0) value.substring(0, separator) else value
        val display = if (separator >= 0 && separator + 1 < value.length) {
            value.substring(separator + 1)
        } else {
            ""
        }
        return safePath(path) + ",displayLen=" + display.length
    }

    fun safePath(path: String?): String {
        if (path.isNullOrBlank()) return "path=none"
        val extension = runCatching {
            File(path).extension.lowercase(Locale.ROOT)
        }.getOrDefault("").ifBlank { "none" }
        return "ext=$extension,pathHash=" +
            Integer.toHexString(path.hashCode()) +
            ",pathLen=" + path.length
    }
}
