package io.github.alagga.gonesmart

/**
 * Track Mix starts Auto-DJ from one already-playing native seed. GMMP's normal
 * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. On 4.2.1 GoneSmart prepares both its
 * selected-seed recommendation pool and GMMP's native Auto-DJ candidate pool
 * while the original source queue still protects current+1. Only then is the
 * source queue cleared and the real Initial Size inserted.
 */
internal object TrackMixInitialFillPolicy {
    enum class NativeRefillAction {
        NORMAL,
        PASS_NATIVE,
        SUPPRESS
    }

    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
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
        // PRIME_NATIVE deliberately bypasses GoneSmart selection even though
        // it is an explicit Track Mix qr.z(1): its sole purpose is to make
        // GMMP finish native candidate preparation while the old queue is safe.
        if (!legacyQueue && stage == "PRIME_NATIVE") {
            return NativeRefillAction.PASS_NATIVE
        }
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        if (legacyQueue) return NativeRefillAction.SUPPRESS
        return when (stage) {
            "WAIT_PLAY" -> NativeRefillAction.PASS_NATIVE
            "PREWARM", "CLEARING", "FILLING" -> NativeRefillAction.SUPPRESS
            else -> NativeRefillAction.NORMAL
        }
    }

    /**
     * Once Track Mix has reduced the queue to its selected native seed, never
     * wait on network/provider work. Selected-seed matching was already done
     * before the clear; any genuine shortfall falls through natively.
     */
    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill

    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)

    fun preClearNativePrimeRefillCount(
        totalMissing: Int,
        legacyQueue: Boolean
    ): Int {
        if (legacyQueue) return 0
        return totalMissing.coerceAtLeast(0).coerceAtMost(1)
    }
}
