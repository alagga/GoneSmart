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
        // R57 restores the 4.2.0 barrier semantics for both GMMP versions:
        // while Track Mix is still establishing its exact one-row seed, every
        // incidental refill is held. After isolation TrackMix releases the hold
        // BEFORE sending native AUTO_DJ, so normal GMMP refill hooks run.
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        return NativeRefillAction.SUPPRESS
    }

    fun usePredecoderCompatibility(
        source: String,
        legacyQueue: Boolean
    ): Boolean {
        // R61 device evidence proved Queue-context has the same 4.2.1
        // predecoder timing requirement as the other song-menu starts. Keep
        // source in the policy API for diagnostics/future source-specific gates.
        return !legacyQueue
    }

    fun requiresPreparedSeedIsolation(
        source: String,
        legacyQueue: Boolean
    ): Boolean {
        // R63: a Queue-context Play moves Current inside the existing queue.
        // Prepared Smart bootstrap must first restore the 4.2.0 one-seed
        // position-1 invariant or old followers survive ahead of the new pool.
        return !legacyQueue && source == "menu_gm_context_queue"
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

    fun isExactInitialQueue(
        initialSize: Int,
        currentQueuePosition: Int?,
        queuePositions: List<Int>
    ): Boolean {
        val expectedSize = initialSize.coerceAtLeast(1)
        if (currentQueuePosition != 1) return false
        if (queuePositions.size != expectedSize) return false
        return queuePositions == (1..expectedSize).toList()
    }

    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill
}
