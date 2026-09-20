package io.github.alagga.gonesmart

enum class QueueItemState {
    PAST,
    CURRENT,
    UPCOMING
}

data class TrackInfo(
    val id: Long,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val path: String?
)

data class QueueItemInfo(
    val orderIndex: Int,
    val queuePosition: Int,
    val shufflePosition: Int,
    val queueEntryId: Long,
    val track: TrackInfo,
    val state: QueueItemState
)

data class QueueContext(
    val currentQueuePosition: Int,
    val items: List<QueueItemInfo>
) {
    val pastItems: List<QueueItemInfo>
        get() = items.filter {
            it.state == QueueItemState.PAST
        }

    val currentItem: QueueItemInfo?
        get() = items.firstOrNull {
            it.state == QueueItemState.CURRENT
        }

    val upcomingItems: List<QueueItemInfo>
        get() = items.filter {
            it.state == QueueItemState.UPCOMING
        }
}