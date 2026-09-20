package io.github.alagga.gonesmart

object GoneSmartRuntimeContract {
    const val ACTION_RUNTIME_EVENT =
        "io.github.alagga.gonesmart.action.RUNTIME_EVENT"

    const val EXTRA_MODE = "mode"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_EVENT = "event"

    const val MODE_NONE = "NONE"
    const val MODE_SMART = "SMART"
    const val MODE_FALLBACK = "FALLBACK"
    const val MODE_STOPPED = "STOPPED"
}
