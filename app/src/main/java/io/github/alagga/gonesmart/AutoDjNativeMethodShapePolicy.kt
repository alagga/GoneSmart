package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Validates the method contract before trusting an accepted GMMP R8 alias. */
internal object AutoDjNativeMethodShapePolicy {
    private fun isInstanceIntMethod(method: Method): Boolean =
        method.parameterCount == 1 && method.parameterTypes[0] == Integer.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)

    fun isRefill(method: Method): Boolean =
        isInstanceIntMethod(method) && method.returnType == Void.TYPE

    fun isSelection(method: Method): Boolean =
        isInstanceIntMethod(method) &&
            java.util.List::class.java.isAssignableFrom(method.returnType)
}
