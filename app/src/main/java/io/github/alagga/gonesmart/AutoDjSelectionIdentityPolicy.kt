package io.github.alagga.gonesmart

import java.util.IdentityHashMap

/**
 * GMMP owns its selection objects. They are mutable native rows, so writing
 * different Track IDs into two list slots that alias one object would turn
 * both slots into the last written Track ID.
 */
internal object AutoDjSelectionIdentityPolicy {
    fun uniqueRows(rows: List<*>): List<Any> {
        val seen = IdentityHashMap<Any, Boolean>()
        return rows.mapNotNull { row ->
            if (row != null && seen.put(row, true) == null) row else null
        }
    }
}
