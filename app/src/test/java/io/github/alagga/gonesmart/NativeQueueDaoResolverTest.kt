package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueueDaoResolverTest {
    private interface Binder {
        fun bindLong(index: Int, value: Long)
    }

    private data class QueueRow(
        val queueId: Long,
        val trackId: Long,
        val position: Int
    )

    private data class TrackRow(
        val songId: Long
    )

    private abstract class QueueContract
    private abstract class TrackContract

    private class QueueDao : QueueContract() {
        @Suppress("unused")
        private val update = QueueAdapter()

        private class QueueAdapter {
            fun M(): String =
                "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

            fun G(statement: Binder, value: Any) {
                val row = value as QueueRow
                statement.bindLong(1, row.position.toLong())
                statement.bindLong(2, row.queueId)
            }
        }
    }

    private class TrackDao : TrackContract() {
        @Suppress("unused")
        private val update = TrackAdapter()

        private class TrackAdapter {
            fun M(): String =
                "UPDATE tracks SET track_name = ? WHERE song_id = ?"

            fun G(statement: Binder, value: Any) {
                val row = value as TrackRow
                statement.bindLong(1, row.songId)
            }
        }
    }

    private open class InheritedDatabaseNoise {
        var inheritedCalls = 0

        @Suppress("unused")
        fun inheritedQueueAccessor(): QueueContract {
            inheritedCalls++
            return QueueDao()
        }
    }

    private class FakeGeneratedDatabase : InheritedDatabaseNoise() {
        val queueDao = QueueDao()
        val trackDao = TrackDao()
        var queueAccessorCalls = 0
        var trackAccessorCalls = 0
        var parameterizedCalls = 0

        @Suppress("unused")
        fun queue(): QueueContract {
            queueAccessorCalls++
            return queueDao
        }

        @Suppress("unused")
        fun tracks(): TrackContract {
            trackAccessorCalls++
            return trackDao
        }

        @Suppress("unused")
        fun parameterized(value: Int): QueueContract {
            parameterizedCalls += value
            return queueDao
        }

        @Suppress("unused")
        fun concreteHelper(): QueueDao = queueDao
    }

    @Test fun queueTableAdapterWinsOverHistoricalTrackDaoCandidate() {
        val trackDao = TrackDao()
        val queueDao = QueueDao()

        val result = NativeQueueDaoResolver.resolveCandidates(
            listOf(
                "autoDj:q" to trackDao,
                "database:queue" to queueDao
            )
        )

        assertSame(queueDao, result?.dao)
        assertEquals(QueueRow::class.java, result?.entity?.modelClass)
        assertEquals(true, result?.evidence?.contains("database:queue"))
    }

    @Test fun duplicateReferencesToSameQueueDaoAreNotAmbiguous() {
        val queueDao = QueueDao()

        val result = NativeQueueDaoResolver.resolveCandidates(
            listOf(
                "autoDj:q" to queueDao,
                "database:queue" to queueDao
            )
        )

        assertSame(queueDao, result?.dao)
    }

    @Test fun twoDistinctQueueOwnersFailClosed() {
        assertNull(
            NativeQueueDaoResolver.resolveCandidates(
                listOf(
                    "first" to QueueDao(),
                    "second" to QueueDao()
                )
            )
        )
    }

    @Test fun tracksOnlyCandidateDoesNotBecomeQueueDao() {
        assertNull(
            NativeQueueDaoResolver.resolveCandidates(
                listOf("autoDj:q" to TrackDao())
            )
        )
    }

    @Test fun generatedDatabaseAccessorsMaterializeDaoCandidatesOnly() {
        val database = FakeGeneratedDatabase()

        val candidates = NativeQueueDaoResolver.databaseAccessorCandidates(
            database
        )
        val result = NativeQueueDaoResolver.resolveCandidates(candidates)

        assertSame(database.queueDao, result?.dao)
        assertEquals(1, database.queueAccessorCalls)
        assertEquals(1, database.trackAccessorCalls)
        assertEquals(0, database.parameterizedCalls)
        assertEquals(0, database.inheritedCalls)
        assertTrue(
            candidates.any {
                it.first.contains("queue():") && it.second === database.queueDao
            }
        )
        assertTrue(
            candidates.any {
                it.first.contains("tracks():") && it.second === database.trackDao
            }
        )
    }

    @Test fun concreteGeneratedHelpersAreNotInvokedAsDaoAccessors() {
        val database = FakeGeneratedDatabase()

        val labels = NativeQueueDaoResolver.databaseAccessorCandidates(database)
            .map { it.first }

        assertFalse(labels.any { it.contains("concreteHelper") })
    }
}
