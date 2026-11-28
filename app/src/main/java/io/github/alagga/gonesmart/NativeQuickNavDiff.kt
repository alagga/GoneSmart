package io.github.alagga.gonesmart

/**
 * qg1's real item order is [metadata, separator, metadata, ...].
 * Retain the shared prefix so entering a folder appends the native
 * separator+metadata pair; returning removes precisely that pair.
 */
internal object NativeQuickNavDiff {
    data class Plan(
        val sharedSegments: Int,
        val retainedItems: Int,
        val removedCount: Int,
        val insertedCount: Int
    ) {
        val isForward: Boolean get() = removedCount == 0 && insertedCount > 0
    }

    // After a root-return removal finishes, or if a new navigation
    // supersedes it, clear all retained holders while hidden.
    fun needsHiddenReset(previousItemCount: Int): Boolean =
        previousItemCount > 0

    fun itemCount(segmentCount: Int): Int =
        if (segmentCount == 0) 0 else segmentCount * 2 - 1

    fun between(old: List<String>, next: List<String>): Plan {
        val common = old.zip(next).takeWhile {
            it.first == it.second
        }.size
        val retained = itemCount(common)
        return Plan(
            sharedSegments = common,
            retainedItems = retained,
            removedCount = itemCount(old.size) - retained,
            insertedCount = itemCount(next.size) - retained
        )
    }
}
