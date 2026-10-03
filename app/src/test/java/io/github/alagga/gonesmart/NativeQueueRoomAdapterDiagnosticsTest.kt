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

            fun G(statement: Binder, value: Any) = Unit
        }

        private class DatabaseLike {
            fun M(): String = "do-not-call"
        }
    }

    private interface Binder

    @Test fun onlyOwnedNestedAdapterSqlIsInspected() {
        val dao = Dao()
        val shape = NativeQueueRoomAdapterDiagnostics.describe(dao)

        assertTrue(shape.contains("queue_table"))
        assertTrue(shape.contains("NativeQueueRoomAdapterDiagnosticsTest\$Dao\$Adapter"))
        assertFalse(shape.contains("do-not-call"))
        assertTrue(dao.dangerousCalls == 0)
    }
}
