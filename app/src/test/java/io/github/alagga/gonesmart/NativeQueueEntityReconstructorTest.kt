package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueueEntityReconstructorTest {
    private interface Binder {
        fun bindLong(index: Int, value: Long)
    }

    private interface AlternateBinder {
        fun bindLong(index: Int, value: Long)
    }

    private data class NativeRow(
        val trackId: Long,
        val queueId: Long,
        val shufflePosition: Int,
        val queuePosition: Int
    )

    private class InsertAdapter {
        fun sql(): String =
            "INSERT OR REPLACE INTO `queue_table` " +
                "(`queue_id`,`queue_track_id`,`queue_position`," +
                "`queue_shuffle_position`) VALUES (?,?,?,?)"

        fun bind(binder: Binder, value: Any) {
            val row = value as NativeRow
            binder.bindLong(1, row.queueId)
            binder.bindLong(2, row.trackId)
            binder.bindLong(3, row.queuePosition.toLong())
            binder.bindLong(4, row.shufflePosition.toLong())
        }
    }

    private class Dao {
        @Suppress("unused")
        private val insertAdapter = InsertAdapter()
    }

    private open class GeneratedAdapterBase {
        @Suppress("unused")
        fun inheritedBind(binder: AlternateBinder, value: Any) {
            val row = value as NativeRow
            binder.bindLong(1, row.queueId)
        }
    }

    private class RuntimeInsertAdapter : GeneratedAdapterBase() {
        fun M(): String =
            "INSERT OR ABORT INTO `queue_table` " +
                "(`queue_position`,`queue_track_id`," +
                "`queue_shuffle_position`,`queue_id`) VALUES (?,?,?,?)"

        fun G(binder: Binder, value: Any) {
            val row = value as NativeRow
            binder.bindLong(1, row.queuePosition.toLong())
            binder.bindLong(2, row.trackId)
            binder.bindLong(3, row.shufflePosition.toLong())
            binder.bindLong(4, row.queueId)
        }
    }

    private class NativeReaderDao(
        private val rows: List<NativeRow>
    ) {
        @Suppress("unused")
        private val insertAdapter = RuntimeInsertAdapter()

        @Suppress("unused")
        fun H1(): ArrayList<NativeRow> = ArrayList(rows)
    }

    private fun context(): QueueContext = QueueContext(
        currentQueuePosition = 2,
        items = listOf(
            QueueItemInfo(
                orderIndex = 0,
                queuePosition = 1,
                shufflePosition = 3,
                queueEntryId = 1001L,
                track = TrackInfo(501L, null, null, null, null),
                state = QueueItemState.PAST
            ),
            QueueItemInfo(
                orderIndex = 1,
                queuePosition = 2,
                shufflePosition = 1,
                queueEntryId = 1002L,
                track = TrackInfo(502L, null, null, null, null),
                state = QueueItemState.CURRENT
            ),
            QueueItemInfo(
                orderIndex = 2,
                queuePosition = 3,
                shufflePosition = 2,
                queueEntryId = 1003L,
                track = TrackInfo(503L, null, null, null, null),
                state = QueueItemState.UPCOMING
            )
        )
    )

    private fun nativeRows(context: QueueContext): List<NativeRow> =
        context.items.map { item ->
            NativeRow(
                trackId = item.track.id,
                queueId = item.queueEntryId,
                shufflePosition = item.shufflePosition,
                queuePosition = item.queuePosition
            )
        }

    @Test fun generatedBinderProvesObfuscatedConstructorOrder() {
        val result = NativeQueueEntityReconstructor.reconstruct(
            dao = Dao(),
            modelClass = NativeRow::class.java,
            context = context()
        )

        assertEquals(3, result?.rows?.size)
        val current = result?.rows?.get(1) as NativeRow
        assertEquals(502L, current.trackId)
        assertEquals(1002L, current.queueId)
        assertEquals(1, current.shufflePosition)
        assertEquals(2, current.queuePosition)
        assertTrue(result.boundary.startsWith("generated-binding:"))
    }

    @Test fun nativeListReaderWinsAfterQueueAdapterProof() {
        val context = context()
        val rows = nativeRows(context)
        val result = NativeQueueEntityReconstructor.reconstruct(
            dao = NativeReaderDao(rows),
            modelClass = NativeRow::class.java,
            context = context
        )

        assertEquals(3, result?.rows?.size)
        assertSame(rows[0], result?.rows?.get(0))
        assertSame(rows[1], result?.rows?.get(1))
        assertTrue(result?.boundary?.startsWith("native-list:") == true)
    }

    @Test fun inheritedAlternateBinderDoesNotMakeRuntimeBinderAmbiguous() {
        val context = context()
        val result = NativeQueueEntityReconstructor.reconstruct(
            dao = NativeReaderDao(nativeRows(context)),
            modelClass = NativeRow::class.java,
            context = context
        )

        assertEquals(3, result?.rows?.size)
        assertTrue(result?.boundary?.contains("H1") == true)
    }

    @Test fun nativeReaderMustCorrelateWithCursorOrFailClosed() {
        val context = context()
        val badRows = nativeRows(context).toMutableList()
        badRows[1] = badRows[1].copy(queueId = 9999L)

        assertNull(
            NativeQueueEntityReconstructor.reconstruct(
                dao = NativeReaderDao(badRows),
                modelClass = NativeRow::class.java,
                context = context
            )
        )
    }

    private class NativePredicate

    private open class PredicateDaoBase(
        @Suppress("unused") private val rows: List<NativeRow>
    ) {
        @Suppress("unused")
        fun read(vararg predicates: NativePredicate): List<NativeRow> =
            if (predicates.isEmpty()) rows else emptyList()
    }

    private class PredicateDao(rows: List<NativeRow>) : PredicateDaoBase(rows)

    @Test fun predicateListBoundaryCannotReconstructQueueEntity() {
        val context = context()
        val rows = nativeRows(context)

        // Device evidence showed this structural family can belong to tracks,
        // even when reached from the Queue DAO hierarchy. Without a generated
        // queue_table binding adapter for the supplied model it must fail
        // closed instead of invoking Predicate[] -> List.
        assertNull(
            NativeQueueEntityReconstructor.reconstruct(
                dao = PredicateDao(rows),
                modelClass = NativePredicate::class.java,
                context = context
            )
        )
    }

    @Test fun insertSqlMustExposeAllQueueColumnsInBindOrder() {
        assertEquals(
            listOf(
                "queue_id",
                "queue_track_id",
                "queue_position",
                "queue_shuffle_position"
            ),
            NativeQueueEntityReconstructor.bindColumns(
                "INSERT INTO `queue_table` " +
                    "(`queue_id`, `queue_track_id`, " +
                    "`queue_position`, `queue_shuffle_position`) " +
                    "VALUES (?,?,?,?)"
            )
        )
        assertNull(
            NativeQueueEntityReconstructor.bindColumns(
                "UPDATE queue_table SET queue_position=? WHERE queue_id=?"
            )
        )
    }
}
