package io.github.alagga.gonesmart

import java.io.ByteArrayOutputStream

/**
 * Persisted Playlist Bridge references.
 *
 * V1 was the first device-proven POC and put a GoneSmart marker directly
 * where GMMP expects a linked .spl path. Keep decoding it so existing test
 * files migrate on their next native save.
 *
 * V2 deliberately remains a syntactically valid native linked-Smart rule:
 *   <neutral native .spl>|<display>|gonesmart-playlist-v2:<hex M3U path>
 *
 * Stock GMMP 4.2.0 reads component 0 as the linked Smart-Playlist path and
 * component 1 as its visible name; it ignores later components. GoneSmart
 * reads the final component and compiles the live ordinary-playlist
 * membership instead. This lets a saved Smart Playlist remain loadable
 * while GoneSmart is disabled.
 */
internal object PlaylistBridgeReference {
    private const val PREFIX_V1 = "gonesmart-playlist:"
    private const val PREFIX_V2 = "gonesmart-playlist-v2:"
    private const val V2_MARKER = "|$PREFIX_V2"

    data class Value(
        val path: String,
        val displayName: String,
        val portable: Boolean
    )

    fun encode(path: String, displayName: String): String {
        require(path.isNotBlank())
        require(displayName.isNotBlank())
        return PREFIX_V1 + encodeHex(path) + "|" + displayName
    }

    fun encodePortable(
        path: String,
        displayName: String,
        compatibilitySmartPlaylistPath: String
    ): String {
        require(path.isNotBlank())
        require(displayName.isNotBlank())
        require(compatibilitySmartPlaylistPath.isNotBlank())
        require(!displayName.contains(V2_MARKER))
        return compatibilitySmartPlaylistPath +
            "|" + displayName +
            V2_MARKER + encodeHex(path)
    }

    fun decode(value: String?): Value? {
        if (value.isNullOrBlank()) return null
        if (value.startsWith(PREFIX_V1)) {
            val separator = value.indexOf('|', PREFIX_V1.length)
            if (separator <= PREFIX_V1.length) return null
            val path = decodeHex(
                value.substring(PREFIX_V1.length, separator)
            ) ?: return null
            val displayName = value.substring(separator + 1)
            if (path.isBlank() || displayName.isBlank()) return null
            return Value(path, displayName, portable = false)
        }

        val marker = value.lastIndexOf(V2_MARKER)
        if (marker <= 0) return null
        val firstSeparator = value.indexOf('|')
        if (firstSeparator <= 0 || firstSeparator >= marker) return null
        val displayName = value.substring(firstSeparator + 1, marker)
        if (displayName.isBlank()) return null
        val encodedPath = value.substring(marker + V2_MARKER.length)
        val path = decodeHex(encodedPath) ?: return null
        if (path.isBlank()) return null
        return Value(path, displayName, portable = true)
    }

    fun isBridgeValue(value: String?): Boolean = decode(value) != null

    private fun encodeHex(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xFF
            chars[index * 2] = alphabet[unsigned ushr 4]
            chars[index * 2 + 1] = alphabet[unsigned and 0x0F]
        }
        return String(chars)
    }

    private fun decodeHex(value: String): String? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val output = ByteArrayOutputStream(value.length / 2)
        var index = 0
        while (index < value.length) {
            val high = value[index].digitToIntOrNull(16) ?: return null
            val low = value[index + 1].digitToIntOrNull(16) ?: return null
            output.write((high shl 4) or low)
            index += 2
        }
        return runCatching {
            output.toByteArray().toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
