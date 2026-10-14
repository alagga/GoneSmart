package io.github.alagga.gonesmart

internal object TrackMixInitialFillPolicy {
    enum class NativeRefillAction { NORMAL, PASS_NATIVE, SUPPRESS }

    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
    private const val GMMP_421_COMMAND_PREPARE_WAIT_MS = 800L

    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    fun nativeRefillAction(
        holdActive: Boolean,
        explicitAllowance: Boolean,
        stage: String?,
        legacyQueue: Boolean
    ): NativeRefillAction {
        if (!legacyQueue && stage == "PRIME_NATIVE" && explicitAllowance) {
            return NativeRefillAction.PASS_NATIVE
        }
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        if (legacyQueue) return NativeRefillAction.SUPPRESS
        return when (stage) {
            "WAIT_PLAY" -> NativeRefillAction.PASS_NATIVE
            "PREWARM", "PRIME_NATIVE", "RETARGETING", "ZERO_POOL_BOUNDARY", "FILLING" ->
                NativeRefillAction.SUPPRESS
            else -> NativeRefillAction.NORMAL
        }
    }

    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)

    fun nativeSlotRefillCount(
        requiredUpcoming: Int,
        availableUpcoming: Int,
        legacyQueue: Boolean
    ): Int {
        if (legacyQueue) return 0
        return (requiredUpcoming.coerceAtLeast(0) -
            availableUpcoming.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill
}
