package io.github.alagga.gonesmart

/**
 * Passive audit of the original GMMP native fallback writer. This never
 * selects a track, changes the queue or starts a provider request.
 */
internal object AutoDjNativeFallbackAuditPolicy {
    data class Row(val entryId: Long, val trackId: Long, val position: Int)
    data class Result(
        val status: String,
        val requested: Int,
        val addedTrackIds: List<Long> = emptyList()
    ) {
        val verified: Boolean get() = status == "complete"
    }

    fun audit(
        before: List<Row>?,
        after: List<Row>?,
        beforeCurrentEntryId: Long?,
        afterCurrentEntryId: Long?,
        requested: Int
    ): Result {
        fun result(reason: String, added: List<Long> = emptyList()) =
            Result(reason, requested, added)
        if (requested <= 0) return result("invalid-request")
        if (before == null || before.isEmpty()) return result("before-queue-unavailable")
        if (after == null || after.isEmpty()) return result("after-queue-unavailable")
        if (beforeCurrentEntryId == null ||
            beforeCurrentEntryId != afterCurrentEntryId
        ) return result("current-entry-changed")
        if (before.map { it.entryId }.distinct().size != before.size ||
            after.map { it.entryId }.distinct().size != after.size
        ) return result("duplicate-queue-entry-id")
        val oldCurrent = before.singleOrNull { it.entryId == beforeCurrentEntryId }
            ?: return result("original-current-missing")
        val newCurrent = after.singleOrNull { it.entryId == beforeCurrentEntryId }
            ?: return result("new-current-missing")
        if (oldCurrent.trackId != newCurrent.trackId) {
            return result("current-track-changed")
        }
        val sorted = after.sortedBy { it.position }
        if (sorted.first().position <= 0 || sorted.map { it.position } !=
            List(sorted.size) { sorted.first().position + it }
        ) return result("queue-positions-not-contiguous")

        val oldFuture = before.filter { it.position >= oldCurrent.position }
            .sortedBy { it.position }
        val newById = after.associateBy { it.entryId }
        if (oldFuture.any {
                newById[it.entryId]?.trackId != it.trackId
            }
        ) return result("existing-current-or-future-changed")
        val newFuturePositions = oldFuture.map { newById.getValue(it.entryId).position }
        if (newFuturePositions != newFuturePositions.sorted()) {
            return result("existing-future-reordered")
        }

        val oldEntryIds = before.mapTo(hashSetOf()) { it.entryId }
        val additions = sorted.filter { it.entryId !in oldEntryIds }
        val addedTrackIds = additions.map { it.trackId }
        if (additions.any { it.trackId <= 0L }) {
            return result("invalid-appended-track-id", addedTrackIds)
        }
        if (additions.any { it.position <= newFuturePositions.last() }) {
            return result("native-rows-not-appended", addedTrackIds)
        }
        if (additions.size > requested) {
            return result("native-overfilled", addedTrackIds)
        }
        if (additions.size < requested) {
            return result(if (additions.isEmpty()) "no-rows-added" else "partial-native-fill",
                addedTrackIds)
        }
        return result("complete", addedTrackIds)
    }
}
