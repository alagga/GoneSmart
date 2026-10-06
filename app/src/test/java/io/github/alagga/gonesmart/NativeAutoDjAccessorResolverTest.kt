package io.github.alagga.gonesmart

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
        var database: Any? = null
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
    fun `ambiguous accessors fail closed`() {
        assertNull(NativeAutoDjAccessorResolver.resolve(AmbiguousService()))
    }
}
