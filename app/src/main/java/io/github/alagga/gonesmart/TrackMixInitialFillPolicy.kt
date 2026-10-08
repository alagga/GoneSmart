package io.github.alagga.gonesmart

/**
 * Track Mix starts Auto-DJ from one already-playing native seed. GMMP's normal
 * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. Track Mix therefore suppresses that
 * transitional refill and invokes the same native refill boundary exactly once
 * with the number of rows missing from Initial Size.
 */
internal object TrackMixInitialFillPolicy {
    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
    // 4.2.1 populated its native Auto-DJ pool in ~86-95 ms in both captured
    // runs, while playback queried the missing position 2 as early as ~208 ms.
    // With the existing 45 ms worker cadence this exits around 90 ms and lets
    // the native qr.z Initial Size refill complete before that lookup.
    private const val GMMP_421_COMMAND_PREPARE_WAIT_MS = 90L

    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)
}
