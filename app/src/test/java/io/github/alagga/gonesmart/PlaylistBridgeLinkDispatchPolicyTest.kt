package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistBridgeLinkDispatchPolicyTest {
    @Test
    fun disabledFeatureAlwaysPassesThrough() {
        assertEquals(
            PlaylistBridgeLinkAction.PASS_THROUGH,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = false,
                edit = false,
                selectedIsBridge = false
            )
        )
    }

    @Test
    fun nativeAddLinkActionShowsTypeMenu() {
        assertEquals(
            PlaylistBridgeLinkAction.SHOW_TYPE_MENU,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = false,
                selectedIsBridge = false
            )
        )
    }

    @Test
    fun bridgeRuleEditUsesBridgeChooser() {
        assertEquals(
            PlaylistBridgeLinkAction.EDIT_BRIDGE,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = true,
                selectedIsBridge = true
            )
        )
    }

    @Test
    fun ordinaryNativeLinkedRuleEditPassesThrough() {
        assertEquals(
            PlaylistBridgeLinkAction.PASS_THROUGH,
            PlaylistBridgeLinkDispatchPolicy.action(
                enabled = true,
                edit = true,
                selectedIsBridge = false
            )
        )
    }
}
