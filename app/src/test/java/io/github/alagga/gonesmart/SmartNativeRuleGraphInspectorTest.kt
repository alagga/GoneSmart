package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartNativeRuleGraphInspectorTest {
    private class Leaf(val operator: Int, val value: String)
    private class Group(val children: List<Any>)
    private class Loop(var child: Any? = null)
    private open class RuleBase
    private class GmmpLeaf(
        val field: Int,
        val operator: Int,
        val value: String,
        val timeUnit: Int,
        val timestamp: Long
    ) : RuleBase()
    private class GmmpGroup(
        val children: List<Any>,
        val matchAll: Boolean,
        val timestamp: Long
    ) : RuleBase()

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

    @Test fun apkShapedNativeRulesAreTraversedWithoutObjectMetadata() {
        val target = "/storage/emulated/0/gmmp/smartlists/Target.spl"
        val safe = GmmpGroup(
            listOf(GmmpLeaf(2, 4, "Trance", 0, 0L)),
            true, 0L
        )
        assertEquals(SmartNativeRuleGraphInspector.Result.CLEAR,
            SmartNativeRuleGraphInspector.inspect(listOf(safe), setOf(target)))
        val linked = GmmpGroup(
            listOf(GmmpLeaf(4, 2, target + "|Target", 0, 0L)),
            false, 0L
        )
        assertEquals(SmartNativeRuleGraphInspector.Result.REFERENCED,
            SmartNativeRuleGraphInspector.inspect(listOf(linked), setOf(target)))
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
