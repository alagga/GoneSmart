package io.github.alagga.gonesmart

/**
 * The tested GMMP AndroidX Fragment lifecycle hook wraps a void method and
 * reads the resulting root via a separate getView():View getter.
 *
 * Keeping the two independent method signatures in a pure validation policy
 * prevents a regression to treating performCreateView's void result as a View.
 */
internal object NativeFragmentViewLifecyclePolicy {
    fun accepts(
        creationReturn: Class<*>,
        viewGetterReturn: Class<*>,
        nativeViewType: Class<*>
    ): Boolean = creationReturn == java.lang.Void.TYPE &&
        nativeViewType.isAssignableFrom(viewGetterReturn)
}
