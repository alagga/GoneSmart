package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private class QueueDao {
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

    private class TrackDao {
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
}
