package io.github.alagga.gonesmart

/**
 * The toolbar action may be owned directly only when the exact native action
 * itself is accepted. GMMP 4.2.1 is different: its proven Link boundary is the
 * downstream chooser callback (`as4$g.accept`). Keeping the original toolbar
 * listener intact is what lets that callback/payload be captured and resumed
 * for the native Smart-Playlist branch.
 */
internal object PlaylistBridgeToolbarDispatchPolicy {
    fun installDirectListener(hasAcceptedLegacyAction: Boolean): Boolean =
        hasAcceptedLegacyAction
}
