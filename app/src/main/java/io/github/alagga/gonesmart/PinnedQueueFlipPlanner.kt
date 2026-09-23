package io.github.alagga.gonesmart

/**
 * Reverse the ENTIRE queue while pinning the current entry to its original
 * absolute index. History and upcoming tracks form ONE combined sequence.
 *
 * This is a pure permutation: it never mutates the original collection or
 * queue entries. It deliberately operates on unique entry instances, not
 * track IDs: the same track can appear more than once in a GMMP queue.
 *
 * GMMP's playing/paused state is irrelevant to this transformation.
 *
 * Example: [A, B, CURRENT, D, E] -> [E, D, CURRENT, B, A].
 */
internal object PinnedQueueFlipPlanner {
    fun <T> flipPinned(items: List<T>, pinnedIndex: Int): List<T> {
        if (items.isEmpty()) {
            require(pinnedIndex == -1) {
                "Empty queue must use pinnedIndex=-1"
            }
            return emptyList()
        }
        require(pinnedIndex in items.indices) {
            "Pinned current index $pinnedIndex outside queue"
        }
        if (items.size == 1) return items.toList()

        val current = items[pinnedIndex]
        val reversedOthers = items.withIndex()
            .filter { (index, _) -> index != pinnedIndex }
            .map { (_, value) -> value }
            .asReversed()
            .iterator()

        return items.indices.map { index ->
            if (index == pinnedIndex) current
            else reversedOthers.next()
        }
    }
}
