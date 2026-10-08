package io.github.alagga.gonesmart

/**
 * Pure planning for the GMMP 4.2.1 Track Auto-DJ queue-position repair.
 *
 * Track Auto-DJ can intentionally delete thousands of source rows while
 * preserving one native queue entry. 4.2.0 exposed the queue append allocator
 * directly and reset it with the seed. The equivalent 4.2.1 allocator is not
 * behaviorally proven, so the compatibility path must never guess a field.
 * Instead, after a native refill, a small Track-Auto-DJ-owned queue may be
 * compacted through the already-proven native Queue DAO update writer while
 * preserving the naturally verified absolute Current position.
 */
internal object QueuePositionNormalizationPolicy {
    data class Row(
        val queueId: Long,
        val position: Int
    )

    data class Plan(
        val orderedQueueIds: List<Long>,
        val originalPositions: List<Int>,
        val normalizedPositions: List<Int>,
        val currentQueueId: Long,
        val currentNewPosition: Int
    )

    fun plan(rows: List<Row>, currentQueueId: Long): Plan? {
        if (rows.isEmpty()) return null
        require(rows.all { it.position > 0 }) {
            "Queue positions must be positive"
        }
        require(rows.map { it.queueId }.toSet().size == rows.size) {
            "Queue IDs must be unique"
        }
        require(rows.map { it.position }.toSet().size == rows.size) {
            "Queue positions must be unique"
        }

        val ordered = rows.sortedBy { it.position }
        val currentIndex = ordered.indexOfFirst { it.queueId == currentQueueId }
        require(currentIndex >= 0) {
            "Current queue entry must exist in the native rows"
        }

        val original = ordered.map { it.position }
        val currentPosition = ordered[currentIndex].position
        val firstNormalizedPosition = currentPosition - currentIndex
        require(firstNormalizedPosition > 0) {
            "Current position cannot anchor a positive contiguous queue"
        }
        val normalized = List(ordered.size) { offset ->
            firstNormalizedPosition + offset
        }
        if (original == normalized) return null

        return Plan(
            orderedQueueIds = ordered.map { it.queueId },
            originalPositions = original,
            normalizedPositions = normalized,
            currentQueueId = currentQueueId,
            currentNewPosition = currentPosition
        )
    }
}
