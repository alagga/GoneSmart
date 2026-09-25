package io.github.alagga.gonesmart

/**
 * Models RecyclerView-style insertion transitions without editing or
 * reordering GMMP's original model. Only pure additions that preserve
 * every existing row's relative order are animated.
 */
internal object PlaylistFolderInsertionPlanner {
    data class Plan(
        val nextOrder: List<String>,
        val newKeys: Set<String>,
        /** Number of newly inserted native rows before each row. */
        val shiftBefore: List<Int>
    )

    fun plan(previous: List<String>, next: List<String>): Plan? {
        if (previous.isEmpty() || next.size <= previous.size ||
            previous.size != previous.toSet().size ||
            next.size != next.toSet().size
        ) return null
        val old = previous.toSet()
        if (next.filter { it in old } != previous) return null
        val inserted = next.filterNot { it in old }.toSet()
        // A complete adapter replacement / scan is not an item insertion.
        if (inserted.isEmpty() || inserted.size > 4) return null
        var addedBefore = 0
        val shifts = next.map { key ->
            val shift = addedBefore
            if (key in inserted) addedBefore++
            shift
        }
        return Plan(next.toList(), inserted, shifts)
    }
}
