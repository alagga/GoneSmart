package io.github.alagga.gonesmart

import java.io.ByteArrayOutputStream

internal object PlaylistBridgeReference {
    private const val PREFIX = "gonesmart-playlist:"

    data class Value(
        val path: String,
        val displayName: String
    )

    fun encode(path: String, displayName: String): String {
        require(path.isNotBlank())
        require(displayName.isNotBlank())
        return PREFIX + encodeHex(path) + "|" + displayName
    }

    fun decode(value: String?): Value? {
        if (value.isNullOrBlank() || !value.startsWith(PREFIX)) return null
        val separator = value.indexOf('|', PREFIX.length)
        if (separator <= PREFIX.length) return null
        val encodedPath = value.substring(PREFIX.length, separator)
        val displayName = value.substring(separator + 1)
        if (displayName.isBlank()) return null
        val path = decodeHex(encodedPath) ?: return null
        if (path.isBlank()) return null
        return Value(path = path, displayName = displayName)
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
