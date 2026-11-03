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

/**
 * Read-only confirmation that GMMP's native qr.z actually appended real
 * queue entries while preserving the Current track and its future entries.
 */
internal object AutoDjQueueAppendPolicy {
    fun confirmed(
        before: List<Pair<Long, Int>>,
        after: List<Pair<Long, Int>>,
        currentEntryId: Long?,
        afterCurrentEntryId: Long?,
        requested: Int
    ): Boolean {
        if (requested <= 0 || currentEntryId == null ||
            currentEntryId != afterCurrentEntryId ||
            before.isEmpty() || after.isEmpty()
        ) return false
        if (before.map { it.first }.distinct().size != before.size ||
            after.map { it.first }.distinct().size != after.size
        ) return false
        val ordered = after.sortedBy { it.second }
        if (ordered.map { it.second } !=
            List(ordered.size) { ordered.first().second + it }
        ) return false
        val current = before.singleOrNull { it.first == currentEntryId }
            ?: return false
        val expectedFuture = before.filter { it.second >= current.second }
        val finalById = after.associate { it.first to it.second }
        if (expectedFuture.any { it.first !in finalById }) return false
        val oldIds = before.mapTo(hashSetOf()) { it.first }
        val added = ordered.filter { it.first !in oldIds }
        if (added.isEmpty() || added.size > requested) return false
        val maxPriorPosition = expectedFuture.mapNotNull {
            finalById[it.first]
        }.maxOrNull() ?: return false
        return added.all { it.second > maxPriorPosition }
    }
}
