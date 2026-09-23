package io.github.alagga.gonesmart

/**
 * Native GMMP's Initial Size includes the selected seed song.
 * Never request an extra recommendation when the requested total is
 * already met, and never treat a negative/empty queue as complete.
 */
internal object TrackMixPlan {
    fun additionalTracksNeeded(initialSize: Int, actualQueueSize: Int): Int {
        require(initialSize > 0) { "Initial queue size must be positive" }
        require(actualQueueSize >= 0) { "Queue size must not be negative" }
        return (initialSize - actualQueueSize).coerceAtLeast(0)
    }

    fun hasEnoughTracks(initialSize: Int, actualQueueSize: Int): Boolean =
        additionalTracksNeeded(initialSize, actualQueueSize) == 0

    /**
     * Both words must come from the installed GMMP language resources.
     * "Mix" is a GoneSmart-only word without a native translation, so
     * use the same two native GMMP nouns for EVERY player language.
     */
    fun localizedMenuLabel(
        language: String,
        nativeTrack: String?,
        nativeAutoDj: String?
    ): String {
        val track = nativeTrack?.takeIf { it.isNotBlank() }
            ?: when (language.lowercase(java.util.Locale.ROOT)) {
                "de" -> "Titel"
                "en" -> "Track"
                else -> null
            }
        val dj = nativeAutoDj?.takeIf { it.isNotBlank() } ?: "Auto-DJ"
        return listOfNotNull(track, dj).joinToString(" ")
    }

    fun localizedStartedMessage(
        language: String,
        menuLabel: String,
        gmmpStarted: String?
    ): String {
        val native = gmmpStarted?.takeIf { it.isNotBlank() }
        return when {
            native != null -> "$menuLabel $native"
            language.equals("de", ignoreCase = true) ->
                "$menuLabel gestartet"
            language.equals("en", ignoreCase = true) ->
                "$menuLabel started"
            else -> "$menuLabel ✓"
        }
    }

    data class NativeQueueEntry(
        val queueId: Long,
        val trackId: Long,
        val position: Int
    )

    data class NativeIsolationPlan(
        val selectedEntryId: Long,
        val originalPosition: Int,
        val removeEntryIds: List<Long>
    )

    /**
     * A selected queue ROW is identified by queue_id, not just track_id.
     * This handles duplicate tracks and pre-existing playback history.
     * Failure to identify the exact selected native row aborts the
     * transaction rather than clearing an unrelated queue.
     */
    fun planNativeIsolation(
        entries: List<NativeQueueEntry>,
        currentPosition: Int,
        selectedTrackId: Long
    ): NativeIsolationPlan {
        require(entries.isNotEmpty()) { "Native queue is empty" }
        require(entries.map { it.queueId }.distinct().size == entries.size) {
            "Native queue IDs are not unique"
        }
        require(entries.map { it.position }.distinct().size == entries.size) {
            "Native queue positions are not unique"
        }
        val current = entries.singleOrNull { it.position == currentPosition }
            ?: error("Native current queue entry unavailable")
        require(current.trackId == selectedTrackId) {
            "The current native song changed before isolation"
        }
        return NativeIsolationPlan(
            selectedEntryId = current.queueId,
            originalPosition = current.position,
            removeEntryIds = entries.filter {
                it.queueId != current.queueId
            }.map { it.queueId }
        )
    }

    /**
     * A new native track menu must always put the action immediately
     * after "Play next" without reordering any other native items.
     */
    fun insertionIndex(playNextIndex: Int, itemCount: Int): Int {
        require(playNextIndex in 0 until itemCount)
        return playNextIndex + 1
    }
}
