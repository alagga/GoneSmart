package io.github.alagga.gonesmart

enum class QueueEntryOrigin {
    USER_QUEUE,
    GONESMART,
    NATIVE_AUTO_DJ
}

data class QueueSessionSnapshot(
    val sessionId: Long,
    val isNewSession: Boolean,
    val reason: String,
    val currentItem: QueueItemInfo?,
    val pastItems: List<QueueItemInfo>,
    val upcomingItems: List<QueueItemInfo>,
    val manualUpcomingItems: List<QueueItemInfo>,
    val sessionItems: List<QueueItemInfo>,
    val userAnchorTracks: List<TrackInfo>,
    val recentGeneratedTracks: List<TrackInfo>,
    val originsByEntryId: Map<Long, QueueEntryOrigin>
) {
    fun originOf(
        item: QueueItemInfo
    ): QueueEntryOrigin? {

        return originsByEntryId[
            item.queueEntryId
        ]
    }
}

class QueueSessionTracker {

    companion object {

        /*
         * Session anchors survive even after GMMP removes the
         * corresponding queue rows. This is intentional: a
         * user-created Rock queue must remain the anchor of the
         * session instead of being replaced by the first track
         * GoneSmart happened to append.
         */
        private const val MAX_USER_ANCHORS =
            8

        private const val MAX_RECENT_GENERATED_CONTEXT =
            3
    }

    private var initialized = false
    private var sessionId = 0L
    private var previousItemsByEntryId = emptyMap<Long, QueueItemInfo>()

    private val sessionMemberEntryIds =
        mutableSetOf<Long>()

    private val originsByEntryId =
        mutableMapOf<Long, QueueEntryOrigin>()

    private val userAnchorTracksById =
        linkedMapOf<Long, TrackInfo>()

    private val recentGeneratedTracksById =
        linkedMapOf<Long, TrackInfo>()

    @Synchronized
    fun observe(
        context: QueueContext
    ): QueueSessionSnapshot {

        val previousIds =
            previousItemsByEntryId.keys

        val currentIds =
            context
                .items
                .map {
                    it.queueEntryId
                }
                .toSet()

        val decision =
            detectSessionBoundary(
                context = context,
                previousIds = previousIds,
                currentIds = currentIds
            )

        if (
            decision.isNewSession
        ) {

            sessionId +=
                1L

            sessionMemberEntryIds.clear()
            originsByEntryId.clear()
            userAnchorTracksById.clear()
            recentGeneratedTracksById.clear()

            val newEntryIds =
                currentIds -
                    previousIds

            val initialMembers =
                if (
                    !initialized
                ) {

                    context.items

                } else {

                    context
                        .items
                        .filter { item ->

                            item.state !=
                                QueueItemState.PAST ||
                                item.queueEntryId in
                                newEntryIds
                        }
                }
                    .toMutableList()

            context
                .currentItem
                ?.let { current ->

                    if (
                        initialMembers.none {
                            it.queueEntryId ==
                                current.queueEntryId
                        }
                    ) {

                        initialMembers +=
                            current
                    }
                }

            initialMembers
                .sortedBy {
                    it.queuePosition
                }
                .forEach { item ->

                    registerUserQueueItem(
                        item
                    )
                }

        } else {

            /*
             * Queue entries that appear outside our own refill
             * hook are deliberate/external queue content and
             * therefore become session anchors.
             */
            context
                .items
                .sortedBy {
                    it.queuePosition
                }
                .forEach { item ->

                    if (
                        item.queueEntryId !in
                        sessionMemberEntryIds
                    ) {

                        registerUserQueueItem(
                            item
                        )
                    }
                }
        }

        /*
         * Queue-entry bookkeeping only tracks rows GMMP still
         * exposes. User anchors themselves intentionally persist
         * for the whole session in userAnchorTracksById.
         */
        sessionMemberEntryIds
            .retainAll(
                currentIds
            )

        originsByEntryId
            .keys
            .retainAll(
                currentIds
            )

        previousItemsByEntryId =
            context
                .items
                .associateBy {
                    it.queueEntryId
                }

        initialized =
            true

        return buildSnapshot(
            context = context,
            isNewSession = decision.isNewSession,
            reason = decision.reason
        )
    }

    @Synchronized
    fun commitGeneratedAdditions(
        beforeContext: QueueContext,
        afterContext: QueueContext,
        origin: QueueEntryOrigin
    ): List<QueueItemInfo> {

        val beforeIds =
            beforeContext
                .items
                .map {
                    it.queueEntryId
                }
                .toSet()

        val addedItems =
            afterContext
                .items
                .filter {
                    it.queueEntryId !in
                        beforeIds
                }

        addedItems
            .forEach { item ->

                sessionMemberEntryIds +=
                    item.queueEntryId

                originsByEntryId[
                    item.queueEntryId
                ] =
                    origin

                if (
                    origin ==
                    QueueEntryOrigin.GONESMART
                ) {

                    rememberGeneratedTrack(
                        item.track
                    )
                }
            }

        previousItemsByEntryId =
            afterContext
                .items
                .associateBy {
                    it.queueEntryId
                }

        initialized =
            true

        return addedItems
    }

    @Synchronized
    fun originOf(
        queueEntryId: Long
    ): QueueEntryOrigin? {

        return originsByEntryId[
            queueEntryId
        ]
    }

    private fun registerUserQueueItem(
        item: QueueItemInfo
    ) {

        sessionMemberEntryIds +=
            item.queueEntryId

        originsByEntryId[
            item.queueEntryId
        ] =
            QueueEntryOrigin.USER_QUEUE

        rememberUserAnchor(
            item.track
        )
    }

    private fun rememberUserAnchor(
        track: TrackInfo
    ) {

        /* Move repeated tracks to the newest anchor position. */
        userAnchorTracksById.remove(
            track.id
        )

        userAnchorTracksById[
            track.id
        ] =
            track

        while (
            userAnchorTracksById.size >
            MAX_USER_ANCHORS
        ) {

            val oldestKey =
                userAnchorTracksById
                    .keys
                    .firstOrNull()
                    ?: break

            userAnchorTracksById.remove(
                oldestKey
            )
        }
    }

    private fun rememberGeneratedTrack(
        track: TrackInfo
    ) {

        recentGeneratedTracksById.remove(
            track.id
        )

        recentGeneratedTracksById[
            track.id
        ] =
            track

        while (
            recentGeneratedTracksById.size >
            MAX_RECENT_GENERATED_CONTEXT
        ) {

            val oldestKey =
                recentGeneratedTracksById
                    .keys
                    .firstOrNull()
                    ?: break

            recentGeneratedTracksById.remove(
                oldestKey
            )
        }
    }

    private fun buildSnapshot(
        context: QueueContext,
        isNewSession: Boolean,
        reason: String
    ): QueueSessionSnapshot {

        val sessionItems =
            context
                .items
                .filter {
                    it.queueEntryId in
                        sessionMemberEntryIds
                }

        val currentItem =
            sessionItems
                .firstOrNull {
                    it.state ==
                        QueueItemState.CURRENT
                }

        val pastItems =
            sessionItems
                .filter {
                    it.state ==
                        QueueItemState.PAST
                }

        val upcomingItems =
            sessionItems
                .filter {
                    it.state ==
                        QueueItemState.UPCOMING
                }

        val manualUpcomingItems =
            upcomingItems
                .filter { item ->

                    originsByEntryId[
                        item.queueEntryId
                    ] ==
                        QueueEntryOrigin.USER_QUEUE
                }

        return QueueSessionSnapshot(
            sessionId = sessionId,
            isNewSession = isNewSession,
            reason = reason,
            currentItem = currentItem,
            pastItems = pastItems,
            upcomingItems = upcomingItems,
            manualUpcomingItems = manualUpcomingItems,
            sessionItems = sessionItems,
            userAnchorTracks =
                userAnchorTracksById
                    .values
                    .toList(),
            recentGeneratedTracks =
                recentGeneratedTracksById
                    .values
                    .toList(),
            originsByEntryId =
                originsByEntryId
                    .toMap()
        )
    }

    private fun detectSessionBoundary(
        context: QueueContext,
        previousIds: Set<Long>,
        currentIds: Set<Long>
    ): SessionBoundaryDecision {

        if (
            !initialized
        ) {

            return SessionBoundaryDecision(
                isNewSession = true,
                reason = "first observation"
            )
        }

        if (
            currentIds.isEmpty()
        ) {

            return SessionBoundaryDecision(
                isNewSession = false,
                reason = "empty queue"
            )
        }

        if (
            previousIds.isEmpty()
        ) {

            return SessionBoundaryDecision(
                isNewSession = true,
                reason = "queue created after empty state"
            )
        }

        if (
            previousIds ==
            currentIds
        ) {

            return SessionBoundaryDecision(
                isNewSession = false,
                reason = "same queue entries"
            )
        }

        val sharedCount =
            previousIds
                .count {
                    it in currentIds
                }

        val referenceSize =
            minOf(
                previousIds.size,
                currentIds.size
            )
                .coerceAtLeast(
                    1
                )

        val sharedRatio =
            sharedCount.toDouble() /
                referenceSize.toDouble()

        val currentEntryId =
            context
                .currentItem
                ?.queueEntryId

        val currentWasKnown =
            currentEntryId != null &&
                currentEntryId in
                previousIds

        val sharedActiveCount =
            context
                .items
                .count { item ->

                    item.queueEntryId in
                        previousIds &&
                        item.state !=
                        QueueItemState.PAST
                }

        val newActiveCount =
            context
                .items
                .count { item ->

                    item.queueEntryId !in
                        previousIds &&
                        item.state !=
                        QueueItemState.PAST
                }

        /*
         * GMMP may retain already-played rows while a new
         * queue is loaded. If only old PAST entries overlap
         * but the current/upcoming part is new, this is a
         * new GoneSmart queue session.
         */
        if (
            !currentWasKnown &&
            newActiveCount > 0 &&
            sharedActiveCount == 0
        ) {

            return SessionBoundaryDecision(
                isNewSession = true,
                reason = "new active queue with only old past overlap"
            )
        }

        /*
         * Normal playback/skip inside an existing small
         * queue must not create a new session.
         */
        if (
            currentWasKnown &&
            referenceSize <= 3
        ) {

            return SessionBoundaryDecision(
                isNewSession = false,
                reason = "known current item in small queue"
            )
        }

        if (
            currentWasKnown &&
            sharedRatio >= 0.50
        ) {

            return SessionBoundaryDecision(
                isNewSession = false,
                reason = "majority of queue retained"
            )
        }

        if (
            sharedRatio >= 0.65
        ) {

            return SessionBoundaryDecision(
                isNewSession = false,
                reason = "strong queue overlap"
            )
        }

        /*
         * A large queue where only the old current item
         * survives is effectively a replaced queue.
         */
        if (
            currentIds.size >= 4 &&
            sharedCount <= 1
        ) {

            return SessionBoundaryDecision(
                isNewSession = true,
                reason = "queue largely replaced"
            )
        }

        if (
            !currentWasKnown &&
            sharedRatio < 0.40
        ) {

            return SessionBoundaryDecision(
                isNewSession = true,
                reason = "new current item with low queue overlap"
            )
        }

        return SessionBoundaryDecision(
            isNewSession = false,
            reason = "queue continuity preserved"
        )
    }

    private data class SessionBoundaryDecision(
        val isNewSession: Boolean,
        val reason: String
    )
}
