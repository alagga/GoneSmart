package io.github.alagga.gonesmart

/**
 * Ordinary Smart Auto-DJ leaves GMMP's current song alone and only refills
 * when the last queue entry has become CURRENT. This check never performs
 * a provider request itself; the accepted qr.z hook may defer selection
 * until the single existing GoneSmart recommendation pipeline completes.
 */
internal object AutoDjWarmQueuePolicy {
    fun refillCount(
        queueSize: Int,
        currentIndex: Int,
        configuredUpcoming: Int
    ): Int {
        if (queueSize <= 0 || currentIndex != queueSize - 1) return 0
        return configuredUpcoming.coerceAtLeast(1)
    }
}
