package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFragmentViewLifecyclePolicyTest {
    private class NativeFragmentLike {
        @Suppress("UNUSED_PARAMETER")
        fun performCreateView(inflater: Any, parent: Any, state: Any) = Unit
        fun getView(): ViewLike = ViewLike()
    }

    private open class ViewLike
    private class OtherView

    @Test fun voidCreationAndIndependentViewGetterAreRequired() {
        val methods = NativeFragmentLike::class.java
        val create = methods.getDeclaredMethod(
            "performCreateView",
            Any::class.java,
            Any::class.java,
            Any::class.java
        )
        val getter = methods.getDeclaredMethod("getView")
        assertTrue(NativeFragmentViewLifecyclePolicy.accepts(
            create.returnType, getter.returnType, ViewLike::class.java
        ))
        assertFalse(NativeFragmentViewLifecyclePolicy.accepts(
            getter.returnType, getter.returnType, ViewLike::class.java
        ))
    }

    @Test fun mismatchedViewGetterCannotMaskAnUnrelatedFragment() {
        assertFalse(NativeFragmentViewLifecyclePolicy.accepts(
            java.lang.Void.TYPE,
            OtherView::class.java,
            ViewLike::class.java
        ))
    }
}
