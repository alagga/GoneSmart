package io.github.alagga.gonesmart

/**
 * Reverse ALL queue entries, including history and upcoming titles.
 *
 * The current song remains the current SONG (playing or paused) rather than
 * being pinned to its old list index. Its new index is n - 1 - oldIndex.
 * Use native GMMP queue APIs to update its playhead after reordering.
 *
 * Track IDs need not be unique; the current entry is tracked by its
 * original queue index, so duplicate song IDs do not affect the result.
 *
 * Example: [A, (B), C, D, E] -> [E, D, C, (B), A].
 */
internal object QueueFlipPlanner {
    data class Plan<T>(
        val entries: List<T>,
        val newCurrentIndex: Int
    )

    fun <T> reverseAll(
        items: List<T>,
        currentIndex: Int
    ): Plan<T> {
        if (items.isEmpty()) {
            require(currentIndex == -1) {
                "Empty queue must use currentIndex=-1"
            }
            return Plan(emptyList(), -1)
        }

        require(currentIndex in items.indices) {
            "Current index $currentIndex outside queue"
        }

        return Plan(
            entries = items.asReversed().toList(),
            newCurrentIndex = items.lastIndex - currentIndex
        )
    }
}
