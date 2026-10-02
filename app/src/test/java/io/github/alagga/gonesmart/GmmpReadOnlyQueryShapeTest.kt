package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmmpReadOnlyQueryShapeTest {
    private class PooledQuery private constructor(@Suppress("unused") slots: Int) {
        companion object {
            @JvmStatic
            fun acquire(sql: String, slots: Int): PooledQuery =
                PooledQuery(slots).also { sql.length }
        }
    }

    private class LegacyQuery(
        @Suppress("unused") sql: String,
        @Suppress("unused") args: Array<Any?>
    )

    private class UnsupportedQuery(@Suppress("unused") slots: Int)

    private interface QueryContract

    @Test fun acceptsGmmp421PooledRoomShape() {
        assertNotNull(GmmpReadOnlyQueryShape.pooledFactory(PooledQuery::class.java))
        assertTrue(GmmpReadOnlyQueryShape.supported(PooledQuery::class.java))
    }

    @Test fun keepsLegacyAndInterfaceShapes() {
        assertNotNull(
            GmmpReadOnlyQueryShape.legacyConstructor(LegacyQuery::class.java)
        )
        assertTrue(GmmpReadOnlyQueryShape.supported(LegacyQuery::class.java))
        assertTrue(GmmpReadOnlyQueryShape.supported(QueryContract::class.java))
    }

    @Test fun rejectsBareIntConstructor() {
        assertFalse(GmmpReadOnlyQueryShape.supported(UnsupportedQuery::class.java))
    }
}
