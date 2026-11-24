package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartNativeRuleGraphInspectorTest {
    private class Leaf(val operator: Int, val value: String)
    private class Group(val children: List<Any>)
    private class Loop(var child: Any? = null)

    @Test fun findsNativeSmartLinkWithoutHardcodedR8Names() {
        val target = "/storage/emulated/0/gmmp/smartlists/Test.spl"
        val tree = listOf(Group(listOf(Leaf(1, target + "|Test"))))
        assertEquals(SmartNativeRuleGraphInspector.Result.REFERENCED,
            SmartNativeRuleGraphInspector.inspect(tree, setOf(target)))
    }

    @Test fun distinguishesNoLinksAndPortablePlaylistBridge() {
        val target = "/storage/emulated/0/gmmp/smartlists/Test.spl"
        val tree = listOf(Group(listOf(
            Leaf(0, "pop|artist"),
            Leaf(1, PlaylistBridgeReference.encodePortable(
                "/storage/emulated/0/gmmp/playlists/Safe.m3u", "Playlist",
                "/storage/emulated/0/gmmp/smartlists/Neutral.spl"
            ))
        )))
        assertEquals(SmartNativeRuleGraphInspector.Result.CLEAR,
            SmartNativeRuleGraphInspector.inspect(tree, setOf(target)))
    }

    @Test fun unknownGraphRemainsBlockedInsteadOfAssumedSafe() {
        assertEquals(SmartNativeRuleGraphInspector.Result.UNKNOWN,
            SmartNativeRuleGraphInspector.inspect(listOf(Any()), setOf("/x.spl")))
    }

    @Test fun cycleAndBudgetAreBounded() {
        val cycle = Loop()
        cycle.child = cycle
        assertEquals(SmartNativeRuleGraphInspector.Result.CLEAR,
            SmartNativeRuleGraphInspector.inspect(listOf(cycle), emptySet()))
        assertEquals(SmartNativeRuleGraphInspector.Result.UNKNOWN,
            SmartNativeRuleGraphInspector.inspect(
                listOf(Group(listOf(Leaf(1,"Other")))), emptySet(), 1))
    }
}
