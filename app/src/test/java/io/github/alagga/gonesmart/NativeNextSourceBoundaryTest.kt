package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeNextSourceBoundaryTest {
    private class Source
    private class NativeService {
        companion object {
            @JvmStatic fun renamedRefresh(service: NativeService) { service.hashCode() }
        }
    }
    private class NativePlayer {
        fun renamedSetter(source: Source?, seamless: Boolean) {
            if (source != null && seamless) source.hashCode()
        }
    }
    private class AmbiguousService {
        companion object {
            @JvmStatic fun one(service: AmbiguousService) { service.hashCode() }
            @JvmStatic fun two(service: AmbiguousService) { service.hashCode() }
        }
    }
    private class AmbiguousPlayer {
        fun one(source: Source?, value: Boolean) { if (value) source?.hashCode() }
        fun two(source: Source?, value: Boolean) { if (value) source?.hashCode() }
    }

    @Test fun uniquelyRenamedMethodsAreDiscoveredWithoutNames() {
        val found = NativeNextSourceBoundary.resolve(
            NativeService::class.java, NativePlayer::class.java
        )
        assertNotNull(found)
        assertEquals("renamedRefresh", found!!.refresh.name)
        assertEquals("renamedSetter", found.setter.name)
        assertEquals(Source::class.java, found.sourceType)
    }

    @Test fun ambiguousShapesDoNotAuthorizeAnySourceRefresh() {
        assertNull(
            NativeNextSourceBoundary.resolve(
                AmbiguousService::class.java, NativePlayer::class.java
            )
        )
        assertNull(
            NativeNextSourceBoundary.resolve(
                NativeService::class.java, AmbiguousPlayer::class.java
            )
        )
    }
}
