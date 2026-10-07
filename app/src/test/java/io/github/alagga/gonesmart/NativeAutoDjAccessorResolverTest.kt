package io.github.alagga.gonesmart

import gonemad.gmmp.data.database.GMDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService

class NativeAutoDjAccessorResolverTest {
    private class NativeState {
        @Suppress("unused")
        fun current(): Int = 3
    }

    private class AutoDjLike {
        @Suppress("unused")
        var executor: ExecutorService? = null
        @Suppress("unused")
        var daoA: Any? = null
        @Suppress("unused")
        var daoB: Any? = null
        @Suppress("unused")
        var database: GMDatabase? = null
        @Suppress("unused")
        var state: NativeState? = NativeState()
    }

    private class LookalikeWithoutDatabase {
        @Suppress("unused")
        var executor: ExecutorService? = null
        @Suppress("unused")
        var daoA: Any? = null
        @Suppress("unused")
        var daoB: Any? = null
        @Suppress("unused")
        var helper: Any? = null
        @Suppress("unused")
        var state: NativeState? = NativeState()
    }

    private class UnrelatedExecutorOwner {
        @Suppress("unused")
        var executor: ExecutorService? = null
        @Suppress("unused")
        var state: NativeState? = NativeState()
    }

    private class Service(
        private val autoDj: AutoDjLike = AutoDjLike()
    ) {
        @Suppress("unused")
        fun strangeObfuscatedAccessor(): AutoDjLike = autoDj

        @Suppress("unused")
        fun unrelated(): UnrelatedExecutorOwner = UnrelatedExecutorOwner()

        @Suppress("unused")
        fun broadLookalike(): LookalikeWithoutDatabase =
            LookalikeWithoutDatabase()
    }

    private class AmbiguousService {
        private val first = AutoDjLike()
        private val second = AutoDjLike()

        @Suppress("unused")
        fun a(): AutoDjLike = first

        @Suppress("unused")
        fun b(): AutoDjLike = second
    }

    @Test
    fun `unique structural accessor resolves without relying on name`() {
        val service = Service()
        val resolution = NativeAutoDjAccessorResolver.resolve(service)

        assertTrue(resolution != null)
        assertEquals("strangeObfuscatedAccessor", resolution!!.accessor.name)
        assertSame(
            service.strangeObfuscatedAccessor(),
            resolution.instance
        )
    }

    @Test
    fun `unrelated executor owner does not satisfy auto dj shape`() {
        assertTrue(
            !NativeAutoDjAccessorResolver.looksLikeAutoDjType(
                UnrelatedExecutorOwner::class.java
            )
        )
    }

    @Test
    fun `executor and integer state without database ownership is rejected`() {
        assertTrue(
            !NativeAutoDjAccessorResolver.looksLikeAutoDjType(
                LookalikeWithoutDatabase::class.java
            )
        )
    }

    @Test
    fun `ambiguous accessors fail closed`() {
        assertNull(NativeAutoDjAccessorResolver.resolve(AmbiguousService()))
    }
}