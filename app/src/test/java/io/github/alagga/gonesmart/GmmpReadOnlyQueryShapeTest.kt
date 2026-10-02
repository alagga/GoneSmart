package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpReadOnlyQueryShapeTest {
    private class FactoryPooledQuery private constructor(
        @Suppress("unused") slots: Int
    ) {
        companion object {
            @JvmStatic
            fun acquire(sql: String, slots: Int): FactoryPooledQuery =
                FactoryPooledQuery(slots).also { sql.length }
        }
    }

    private class DirectPooledQuery private constructor(slots: Int) {
        @Suppress("unused")
        private var capacity: Int = slots
        @Suppress("unused")
        private var argCount: Int = 0
        @Suppress("unused")
        private var sql: String? = null

        @Suppress("unused")
        private fun initialize(sql: String, argCount: Int) {
            this.sql = sql
            this.argCount = argCount
        }
    }

    private class InlinedDirectPooledQuery private constructor(slots: Int) {
        @Suppress("unused")
        private var capacity: Int = slots
        @Suppress("unused")
        private var argCount: Int = 0
        @Suppress("unused")
        private var sql: String? = null
    }

    private class LegacyQuery(
        @Suppress("unused") sql: String,
        @Suppress("unused") args: Array<Any?>
    )

    private class UnsupportedQuery(@Suppress("unused") slots: Int)

    private interface QueryContract

    @Test fun acceptsFactoryPooledRoomShape() {
        assertNotNull(
            GmmpReadOnlyQueryShape.pooledFactory(
                FactoryPooledQuery::class.java
            )
        )
        assertTrue(
            GmmpReadOnlyQueryShape.supported(
                FactoryPooledQuery::class.java
            )
        )
    }

    @Test fun acceptsGmmp421DirectCapacityAndInitializerShape() {
        assertNotNull(
            GmmpReadOnlyQueryShape.capacityConstructor(
                DirectPooledQuery::class.java
            )
        )
        assertNotNull(
            GmmpReadOnlyQueryShape.directInitializer(
                DirectPooledQuery::class.java
            )
        )
        assertTrue(
            GmmpReadOnlyQueryShape.directCarrierSupported(
                DirectPooledQuery::class.java
            )
        )
        val query = GmmpReadOnlyQueryShape.newDirectCarrier(
            DirectPooledQuery::class.java,
            "SELECT 1",
            3
        )!!
        val sql = query.javaClass.getDeclaredField("sql")
            .apply { isAccessible = true }.get(query)
        val count = query.javaClass.getDeclaredField("argCount")
            .apply { isAccessible = true }.getInt(query)
        assertEquals("SELECT 1", sql)
        assertEquals(3, count)
    }

    @Test fun acceptsR8InlinedInitializerOnlyWithStrictRoomFields() {
        assertNotNull(
            GmmpReadOnlyQueryShape.directFieldLayout(
                InlinedDirectPooledQuery::class.java
            )
        )
        assertTrue(
            GmmpReadOnlyQueryShape.supported(
                InlinedDirectPooledQuery::class.java
            )
        )

        val query = GmmpReadOnlyQueryShape.newDirectCarrier(
            InlinedDirectPooledQuery::class.java,
            "SELECT * FROM queue_table",
            4
        )!!
        val sql = query.javaClass.getDeclaredField("sql")
            .apply { isAccessible = true }.get(query)
        val count = query.javaClass.getDeclaredField("argCount")
            .apply { isAccessible = true }.getInt(query)
        assertEquals("SELECT * FROM queue_table", sql)
        assertEquals(4, count)
    }

    @Test fun keepsLegacyAndInterfaceShapes() {
        assertNotNull(
            GmmpReadOnlyQueryShape.legacyConstructor(LegacyQuery::class.java)
        )
        assertTrue(GmmpReadOnlyQueryShape.supported(LegacyQuery::class.java))
        assertTrue(GmmpReadOnlyQueryShape.supported(QueryContract::class.java))
    }

    @Test fun rejectsBareIntConstructorWithoutSqlOwnership() {
        assertFalse(GmmpReadOnlyQueryShape.supported(UnsupportedQuery::class.java))
    }
}
