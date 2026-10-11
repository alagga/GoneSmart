package io.github.alagga.gonesmart

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrackMixAutoDjResolverTest {
    private class FakeAutoDj { @Suppress("UNUSED_PARAMETER") fun z(count: Int) = Unit }
    private data class Holder(val child: Any?)
    @Test fun resolvesOneAcceptedInstanceFromNestedGraph() {
        val target = FakeAutoDj()
        val resolver = TrackMixAutoDjResolver(acceptedClassName = FakeAutoDj::class.java.name)
        assertSame(target, resolver.resolve(listOf(Holder(Holder(target)))))
    }
    @Test fun ambiguityFailsClosed() {
        val resolver = TrackMixAutoDjResolver(acceptedClassName = FakeAutoDj::class.java.name)
        assertNull(resolver.resolve(listOf(Holder(FakeAutoDj()), Holder(FakeAutoDj()))))
    }
    @Test fun unrelatedObjectDoesNotResolve() {
        val resolver = TrackMixAutoDjResolver(acceptedClassName = FakeAutoDj::class.java.name)
        assertNull(resolver.resolve(listOf(Holder("nothing"))))
    }
}
