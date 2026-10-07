package io.github.alagga.gonesmart

/**
 * Fail-open gate for taking visual ownership of GMMP's native Smart list.
 * A resource-id hit alone is not sufficient: setAdapter can occur after
 * onAttachedToWindow, and an unsupported GMMP mapping must remain visible.
 */
internal object SmartFolderSurfaceCompatibilityPolicy {
    fun canMaskNativeList(
        bindingsReady: Boolean,
        adapterPresent: Boolean,
        adapterMatchesBindings: Boolean
    ): Boolean =
        bindingsReady && adapterPresent && adapterMatchesBindings
}
