package io.github.alagga.gonesmart

internal enum class PlaylistBridgeLinkAction {
    PASS_THROUGH,
    SHOW_TYPE_MENU,
    EDIT_BRIDGE
}

internal object PlaylistBridgeLinkDispatchPolicy {
    fun action(
        enabled: Boolean,
        edit: Boolean,
        selectedIsBridge: Boolean
    ): PlaylistBridgeLinkAction {
        if (!enabled) return PlaylistBridgeLinkAction.PASS_THROUGH
        if (!edit) return PlaylistBridgeLinkAction.SHOW_TYPE_MENU
        return if (selectedIsBridge) {
            PlaylistBridgeLinkAction.EDIT_BRIDGE
        } else {
            PlaylistBridgeLinkAction.PASS_THROUGH
        }
    }
}
