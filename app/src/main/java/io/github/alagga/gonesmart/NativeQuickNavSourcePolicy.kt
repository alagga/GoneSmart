package io.github.alagga.gonesmart

/** Read-only evidence for the native QuickNav's alternating item shape. */
internal object NativeQuickNavSourcePolicy {
    fun alternates(count: Int, first: Any?, separator: Any?, next: Any?): Boolean =
        count >= 3 && count % 2 == 1 &&
            first != null && separator != null &&
            first != separator && first == next
}
