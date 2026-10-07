package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueueRoomAdapterDiagnosticsTest {
    private class Dao {
        private val database = DatabaseLike()
        private val adapter = Adapter()

        var dangerousCalls = 0

        fun W1(): Any {
            dangerousCalls++
            return Any()
        }

        private class Adapter {
            fun M(): String =
                "UPDATE queue_table SET queue_position = ? WHERE queue_id = ?"

            fun G(statement: Binder, value: Any) {
                statement.hashCode()
                value.hashCode()
            }
        }

        private class DatabaseLike {
            fun M(): String = "do-not-call"
        }
    }

    private open class BaseDao {
        @Suppress("unused")
        private val inheritedAdapter = Adapter()

        private class Adapter {
            fun M(): String =
                "DELETE FROM queue_table WHERE queue_id = ?"

            fun G(statement: Binder, value: Any) {
                statement.hashCode()
                value.hashCode()
            }
        }
    }

    private class DerivedDao : BaseDao()

    private interface Binder

    @Test fun onlyOwnedNestedAdapterSqlIsInspected() {
        val dao = Dao()
        val shape = NativeQueueRoomAdapterDiagnostics.describe(dao)

        assertTrue(shape.contains("queue_table"))
        assertFalse(shape.contains("do-not-call"))
        assertTrue(dao.dangerousCalls == 0)
    }

    @Test fun inheritedOwnerAdapterIsIncluded() {
        val shape = NativeQueueRoomAdapterDiagnostics.describe(DerivedDao())

        assertTrue(shape.contains("BaseDao"))
        assertTrue(shape.contains("DELETE FROM queue_table"))
    }
}
