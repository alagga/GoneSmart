package io.github.alagga.gonesmart

/**
 * Track Mix starts Auto-DJ from one already-playing native seed. GMMP's normal
 * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. Track Mix therefore suppresses that
 * transitional refill. On 4.2.1 it first requests one native continuity row so
 * playback can never observe an empty next slot, then requests the remaining
 * Initial Size rows after the already-running GoneSmart pool had a bounded
 * chance to become ready. Legacy 4.2.0 keeps its accepted single full refill.
 */
internal object TrackMixInitialFillPolicy {
    enum class NativeRefillAction {
        NORMAL,
        PASS_NATIVE,
        SUPPRESS
    }

    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
    // 4.2.1 populated its native Auto-DJ pool in ~86-95 ms in both captured
    // runs, while playback queried the missing position 2 as early as ~208 ms.
    // With the existing 45 ms worker cadence this exits around 90 ms and lets
    // the native qr.z Initial Size refill complete before that lookup.
    private const val GMMP_421_COMMAND_PREPARE_WAIT_MS = 90L

    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    fun nativeRefillAction(
        holdActive: Boolean,
        explicitAllowance: Boolean,
        stage: String?,
        legacyQueue: Boolean
    ): NativeRefillAction {
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        if (legacyQueue) return NativeRefillAction.SUPPRESS
        return when (stage) {
            "WAIT_PLAY" -> NativeRefillAction.PASS_NATIVE
            "CLEARING", "FILLING" -> NativeRefillAction.SUPPRESS
            else -> NativeRefillAction.NORMAL
        }
    }

    /**
     * Once Track Mix has reduced the queue to its selected native seed,
     * playback continuity outranks waiting for the remote Smart-DJ pool.
     * The pool fill may continue in the background, but GMMP must be
     * allowed to populate its Initial Size immediately.
     */
    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill

    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)

    fun continuityBootstrapRefillCount(
        totalMissing: Int,
        legacyQueue: Boolean
    ): Int {
        val missing = totalMissing.coerceAtLeast(0)
        return if (legacyQueue) missing else missing.coerceAtMost(1)
    }
}
