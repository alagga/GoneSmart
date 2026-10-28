package io.github.alagga.gonesmart

/** Pure parsing and bounds for the user-managed Android logd ring buffer. */
internal object LogcatBufferPolicy {
    const val TARGET_MAIN_BYTES = 8L * 1024 * 1024
    const val MIN_RESTORABLE_BYTES = 64L * 1024
    const val MAX_RESTORABLE_BYTES = 64L * 1024 * 1024

    data class Buffer(val name: String, val sizeBytes: Long)

    private val bufferLine = Regex(
        "(?m)^\\s*(main|system|crash):\\s*ring buffer is\\s+(\\d+)\\s+(B|KiB|MiB|GiB)\\b"
    )

    fun parse(output: String): Map<String, Buffer> = bufferLine.findAll(output).mapNotNull { match ->
        val amount = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val multiplier = when (match.groupValues[3]) {
            "B" -> 1L
            "KiB" -> 1024L
            "MiB" -> 1024L * 1024
            "GiB" -> 1024L * 1024 * 1024
            else -> return@mapNotNull null
        }
        val bytes = runCatching { Math.multiplyExact(amount, multiplier) }.getOrNull()
            ?: return@mapNotNull null
        match.groupValues[1] to Buffer(match.groupValues[1], bytes)
    }.toMap()

    fun restorable(bytes: Long): Boolean =
        bytes in MIN_RESTORABLE_BYTES..MAX_RESTORABLE_BYTES && bytes % 1024 == 0L

    fun format(bytes: Long): String = when {
        bytes % (1024 * 1024) == 0L -> "${bytes / (1024 * 1024)} MiB"
        bytes % 1024 == 0L -> "${bytes / 1024} KiB"
        else -> "$bytes B"
    }

    fun display(buffers: Map<String, Buffer>): String =
        listOf("main", "system", "crash").mapNotNull { name ->
            buffers[name]?.let { "$name: ${format(it.sizeBytes)}" }
        }.joinToString("  •  ")
}
