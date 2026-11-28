package io.github.alagga.gonesmart

/** Chrome must not disappear merely because new native rows are binding. */
internal object SmartQuickNavPresentationPolicy {
    fun chromeReady(headerReady: Boolean, loadingVisible: Boolean): Boolean =
        headerReady || loadingVisible
}
