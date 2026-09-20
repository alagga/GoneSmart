package io.github.alagga.gonesmart

import android.os.SystemClock
import java.util.ArrayDeque


class SessionRecommendationPool {

    companion object {

        /*
         * If a refill produced very few useful local tracks,
         * do not hammer the providers again on every skip.
         */
        private const val MIN_REFILL_ATTEMPT_INTERVAL_MS =
            60_000L
    }

    private var sessionId =
        -1L

    private var targetSize =
        0

    private val pendingTrackIds =
        ArrayDeque<Long>()

    private val usedTrackIds =
        linkedSetOf<Long>()

    private var consumedSinceLastSuccessfulFill =
        0

    private var lastRefillAttemptElapsedMs =
        Long.MIN_VALUE

    @Synchronized
    fun reset(
        newSessionId: Long,
        newTargetSize: Int
    ) {

        sessionId =
            newSessionId

        targetSize =
            newTargetSize
                .coerceAtLeast(
                    1
                )

        pendingTrackIds.clear()
        usedTrackIds.clear()

        consumedSinceLastSuccessfulFill =
            0

        lastRefillAttemptElapsedMs =
            Long.MIN_VALUE
    }

    @Synchronized
    fun configureTarget(
        expectedSessionId: Long,
        newTargetSize: Int
    ) {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return
        }

        targetSize =
            newTargetSize
                .coerceAtLeast(
                    1
                )

        trimToTarget()
    }

    @Synchronized
    fun isForSession(
        expectedSessionId: Long
    ): Boolean {
        return sessionId == expectedSessionId
    }

    @Synchronized
    fun hasAnyPending(): Boolean {
        return pendingTrackIds.isNotEmpty()
    }

    @Synchronized
    fun availableCount(
        expectedSessionId: Long
    ): Int {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return 0
        }

        return pendingTrackIds.size
    }

    @Synchronized
    fun hasEnough(
        expectedSessionId: Long,
        count: Int,
        excludedTrackIds: Set<Long>
    ): Boolean {

        if (
            sessionId !=
            expectedSessionId ||
            count <= 0
        ) {

            return false
        }

        return pendingTrackIds
            .asSequence()
            .filter {
                it !in excludedTrackIds
            }
            .take(
                count
            )
            .count() >=
            count
    }

    @Synchronized
    fun peek(
        expectedSessionId: Long,
        count: Int,
        excludedTrackIds: Set<Long>
    ): List<Long> {

        if (
            sessionId !=
            expectedSessionId ||
            count <= 0
        ) {

            return emptyList()
        }

        pruneExcluded(
            excludedTrackIds
        )

        return pendingTrackIds
            .asSequence()
            .take(
                count
            )
            .toList()
    }

    @Synchronized
    fun commitSelected(
        expectedSessionId: Long,
        selectedTrackIds: List<Long>
    ) {

        if (
            sessionId !=
            expectedSessionId ||
            selectedTrackIds.isEmpty()
        ) {

            return
        }

        selectedTrackIds
            .forEach { trackId ->

                pendingTrackIds.remove(
                    trackId
                )

                usedTrackIds +=
                    trackId
            }

        consumedSinceLastSuccessfulFill +=
            selectedTrackIds.size
    }

    @Synchronized
    fun mergeCandidates(
        expectedSessionId: Long,
        candidateTrackIds: List<Long>,
        newTargetSize: Int
    ): Int {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return 0
        }

        targetSize =
            newTargetSize
                .coerceAtLeast(
                    1
                )

        val alreadyPending =
            pendingTrackIds
                .toSet()

        var added =
            0

        for (
            trackId in
            candidateTrackIds
        ) {

            if (
                pendingTrackIds.size >=
                targetSize
            ) {

                break
            }

            if (
                trackId <= 0L ||
                trackId in usedTrackIds ||
                trackId in alreadyPending ||
                pendingTrackIds.contains(
                    trackId
                )
            ) {

                continue
            }

            pendingTrackIds.addLast(
                trackId
            )

            added +=
                1
        }

        if (
            added > 0
        ) {

            consumedSinceLastSuccessfulFill =
                0
        }

        return added
    }

    @Synchronized
    fun allSeenTrackIds(
        expectedSessionId: Long
    ): Set<Long> {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return emptySet()
        }

        return buildSet {

            addAll(
                usedTrackIds
            )

            addAll(
                pendingTrackIds
            )
        }
    }

    @Synchronized
    fun shouldRefill(
        expectedSessionId: Long,
        sizing: SmartPoolSizing
    ): Boolean {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return false
        }

        val remaining =
            pendingTrackIds.size

        if (
            remaining >
            sizing.lowWaterMark
        ) {

            return false
        }

        val enoughConsumed =
            if (
                remaining == 0
            ) {

                consumedSinceLastSuccessfulFill >=
                    1

            } else {

                consumedSinceLastSuccessfulFill >=
                    sizing.minimumConsumedBeforeRefill
            }

        if (
            !enoughConsumed
        ) {

            return false
        }

        val now =
            SystemClock.elapsedRealtime()

        if (
            lastRefillAttemptElapsedMs !=
            Long.MIN_VALUE &&
            now -
                lastRefillAttemptElapsedMs <
                MIN_REFILL_ATTEMPT_INTERVAL_MS
        ) {

            return false
        }

        return true
    }

    @Synchronized
    fun markRefillAttempt(
        expectedSessionId: Long
    ) {

        if (
            sessionId ==
            expectedSessionId
        ) {

            lastRefillAttemptElapsedMs =
                SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun describe(
        expectedSessionId: Long
    ): String {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return "session-mismatch"
        }

        return "pending=${pendingTrackIds.size} | " +
            "used=${usedTrackIds.size} | " +
            "target=$targetSize | " +
            "consumedSinceFill=$consumedSinceLastSuccessfulFill"
    }

    private fun pruneExcluded(
        excludedTrackIds: Set<Long>
    ) {

        if (
            excludedTrackIds.isEmpty()
        ) {

            return
        }

        val retained =
            pendingTrackIds
                .filter {
                    it !in excludedTrackIds
                }

        pendingTrackIds.clear()

        retained
            .forEach {
                pendingTrackIds.addLast(
                    it
                )
            }
    }

    private fun trimToTarget() {

        while (
            pendingTrackIds.size >
            targetSize
        ) {

            pendingTrackIds.removeLast()
        }
    }
}
