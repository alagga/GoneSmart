package io.github.alagga.gonesmart

object GoneSmartRuntimeContract {
    const val ACTION_RUNTIME_EVENT =
        "io.github.alagga.gonesmart.action.RUNTIME_EVENT"

    // Explicit companion-app command used only when the user presses
    // Track Mix while Smart DJ is switched off.
    const val ACTION_ENABLE_SMART_DJ_FOR_MIX =
        "io.github.alagga.gonesmart.action.ENABLE_SMART_DJ_FOR_MIX"

    const val EXTRA_MODE = "mode"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_EVENT = "event"
    // UI-feature events appear in Logs without replacing Auto-DJ status.
    const val EXTRA_EVENT_ONLY = "event_only"
    const val EXTRA_CATEGORY = "category"

    const val CATEGORY_SMART_DJ = "Smart DJ"
    const val CATEGORY_PLAYLISTS = "Playlists"
    const val CATEGORY_FLIP = "Flip"
    const val CATEGORY_SYSTEM = "System"
    const val CATEGORY_UI = "UI"

    const val MODE_NONE = "NONE"
    const val MODE_SMART = "SMART"
    const val MODE_FALLBACK = "FALLBACK"
    const val MODE_STOPPED = "STOPPED"
}
