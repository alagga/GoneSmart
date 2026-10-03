package io.github.alagga.gonesmart

/**
 * Combines bounded partial emissions from multiple read-only native Queue
 * carriers without requiring an obfuscated entity hint.
 *
 * Rows are grouped strictly by their runtime model class, deduplicated by a
 * caller-supplied native-row fingerprint, and only exposed when the resulting
 * set has exactly the expected Cursor row count and passes the caller's full
 * Cursor correlation predicate. This helper never selects or invokes a writer.
 */
internal object NativeQueuePartialRowAggregator {
    fun aggregate(
        partials: List<Pair<String, List<Any>>>,
        expectedRows: Int,
        fingerprint: (Any) -> String,
        correlates: (List<Any>) -> Boolean
    ): List<Pair<String, List<Any>>> {
        if (expectedRows <= 0 || partials.isEmpty()) return emptyList()

        val grouped = linkedMapOf<Class<*>, MutableList<Pair<String, Any>>>()
        partials.forEach { (source, rows) ->
            rows.forEach { row ->
                grouped.getOrPut(row.javaClass) { arrayListOf() }
                    .add(source to row)
            }
        }

        return grouped.mapNotNull { (model, sourcedRows) ->
            val deduped = linkedMapOf<String, Any>()
            sourcedRows.forEach { (_, row) ->
                deduped.putIfAbsent(fingerprint(row), row)
            }
            if (deduped.size != expectedRows) return@mapNotNull null

            val rows = deduped.values.toList()
            if (!rows.all(model::isInstance) || !correlates(rows)) {
                return@mapNotNull null
            }

            val sources = sourcedRows.map { it.first }.distinct()
            val boundary = "reactive-aggregate:" +
                sources.joinToString("+") { source ->
                    val count = partials
                        .firstOrNull { it.first == source }
                        ?.second
                        ?.count(model::isInstance)
                        ?: 0
                    "$source#$count"
                }
            boundary to rows
        }
    }
}
