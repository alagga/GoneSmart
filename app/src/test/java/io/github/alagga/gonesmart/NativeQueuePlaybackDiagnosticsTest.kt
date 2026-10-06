package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeQueuePlaybackDiagnosticsTest {
    private class StateHost(var o: Int) {
        fun current(): Int = o
        fun setPosition(value: Int) {
            o = value
        }
    }

    private class OtherHost {
        fun constant(): Int = 1
    }

    private class AutoDj(position: Int) {
        @Suppress("unused")
        val p = StateHost(position)

        @Suppress("unused")
        val t = OtherHost()
    }

    private class Service {
        var marker = 4
        fun playbackIndex(): Int = marker
        fun event(@Suppress("UNUSED_PARAMETER") payload: Event) = Unit
        fun oneInt(@Suppress("UNUSED_PARAMETER") value: Int) = Unit
        fun ignoredString(): String = "x"
    }

    private class Event(
        @Suppress("unused") val position: Int,
        @Suppress("unused") val active: Boolean
    )

    @Test
    fun snapshotReportsChangedDxAndServiceSignals() {
        val service = Service()
        val autoDj = AutoDj(7)
        val before = NativeQueuePlaybackDiagnostics.snapshot(service, autoDj)
        autoDj.p.setPosition(8)
        service.marker = 5
        val after = NativeQueuePlaybackDiagnostics.snapshot(service, autoDj)
        val changes = NativeQueuePlaybackDiagnostics.changes(before, after)

        assertTrue(changes.any { it.contains("autoDj.p->") && it.contains("7->8") })
        assertTrue(changes.any { it.contains("service.playbackIndex()") && it.contains("4->5") })
    }

    @Test
    fun stateWriterPolicyFindsOnlyOneIntVoidWriterHosts() {
        val methods = NativeQueuePlaybackDiagnostics.stateWriterMethods(AutoDj::class.java)
        assertEquals(1, methods.size)
        assertEquals("setPosition", methods.single().name)
    }

    @Test
    fun playbackPolicyIncludesEventAndOneIntCommands() {
        val names = NativeQueuePlaybackDiagnostics.playbackMethods(Service::class.java)
            .map { it.name }
            .toSet()
        assertTrue("event" in names)
        assertTrue("oneInt" in names)
        assertTrue("ignoredString" !in names)
    }

    @Test
    fun argumentSummaryExposesOnlyBoundedPrimitiveShape() {
        val summary = NativeQueuePlaybackDiagnostics.describeArguments(
            listOf(Event(8, true))
        )
        assertTrue(summary.contains("position=8"))
        assertTrue(summary.contains("active=true"))
    }
}
