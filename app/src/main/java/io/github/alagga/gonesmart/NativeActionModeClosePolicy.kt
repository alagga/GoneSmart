package io.github.alagga.gonesmart

/**
 * Stable AppCompat resource ownership for contextual-selection close/back.
 *
 * GMMP's obfuscated ActionMode callback can change between releases, while
 * AppCompat's close control remains semantic UI chrome.
 */
internal object NativeActionModeClosePolicy {
    fun isCloseResource(resourceName: String): Boolean =
        resourceName == "action_mode_close_button"

    fun isContextBarResource(resourceName: String): Boolean =
        resourceName == "action_mode_bar" ||
            resourceName == "action_context_bar"
}
