package io.github.alagga.gonesmart

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

data class SmartSelectionSnapshot(
    val trackIds: List<Long>,
    val generatedAtElapsedMs: Long
)

class SmartSelectionCache {

    companion object {

        /*
         * Recommendations sollen nicht ewig
         * weiterverwendet werden, wenn inzwischen
         * ganz andere Tracks laufen.
         *
         * Später ersetzen wir das zusätzlich
         * durch Playback-/Queue-basierte
         * Prefetch-Invalidierung.
         */
        private const val MAX_AGE_MS =
            2L * 60L * 1000L
    }

    private val snapshot =
        AtomicReference<SmartSelectionSnapshot?>(
            null
        )

    fun update(
        trackIds: List<Long>
    ) {

        val uniqueTrackIds =
            trackIds
                .filter {
                    it > 0L
                }
                .distinct()

        if (
            uniqueTrackIds.isEmpty()
        ) {

            clear()

            return
        }

        snapshot.set(
            SmartSelectionSnapshot(
                trackIds =
                    uniqueTrackIds,

                generatedAtElapsedMs =
                    SystemClock.elapsedRealtime()
            )
        )
    }

    fun getTrackIds(
        excludedTrackIds: Set<Long>,
        maxCount: Int
    ): List<Long> {

        if (
            maxCount <= 0
        ) {

            return emptyList()
        }

        val current =
            snapshot.get()
                ?: return emptyList()

        val ageMs =
            SystemClock.elapsedRealtime() -
                    current.generatedAtElapsedMs

        if (
            ageMs < 0L ||
            ageMs >
            MAX_AGE_MS
        ) {

            snapshot.compareAndSet(
                current,
                null
            )

            return emptyList()
        }

        return current
            .trackIds
            .asSequence()
            .filter {
                it !in
                        excludedTrackIds
            }
            .take(
                maxCount
            )
            .toList()
    }

    fun clear() {

        snapshot.set(
            null
        )
    }
}