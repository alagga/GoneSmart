package io.github.alagga.gonesmart

/** JVM-testable guards for selective, bounded diagnostic capture. */
internal object DiagnosticCapturePolicy {
    const val MAX_SNAPSHOT_BYTES = 8_000_000
    const val MAX_SNAPSHOT_LINES = 30_000
    private val THREADTIME_PREFIX = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s")

    /** Only timestamps actually present in the exported lines are evidence. */
    fun threadtimeStamp(line: String): String? =
        THREADTIME_PREFIX.find(line)?.groupValues?.get(1)

    fun uidFilter(goneSmartUid: Int, gmmpUid: Int): String {
        require(goneSmartUid > 0 && gmmpUid > 0) {
            "Both app identities must be resolved before reading Logcat."
        }
        return listOf(goneSmartUid, gmmpUid).distinct().joinToString(",")
    }

    fun shouldTruncate(lines: Int, bytes: Int, incomingBytes: Int): Boolean =
        lines >= MAX_SNAPSHOT_LINES ||
            incomingBytes > MAX_SNAPSHOT_BYTES ||
            bytes > MAX_SNAPSHOT_BYTES - incomingBytes
}
