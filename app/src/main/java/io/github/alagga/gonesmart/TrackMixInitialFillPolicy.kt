package io.github.alagga.gonesmart

/**
 * Track Mix starts Auto-DJ from one already-playing native seed. GMMP's normal
 * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. Track Mix therefore suppresses that
 * transitional refill and invokes the same native refill boundary exactly once
 * with the number of rows missing from Initial Size.
 */
internal object TrackMixInitialFillPolicy {
    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)
}
