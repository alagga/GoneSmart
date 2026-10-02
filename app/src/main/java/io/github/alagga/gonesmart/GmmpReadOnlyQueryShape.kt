package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pure reflection policy for Room's read-only query carrier.
 * Kept Android-free so GMMP remaps can be regression-tested on the JVM.
 */
internal object GmmpReadOnlyQueryShape {
    fun legacyConstructor(type: Class<*>): Constructor<*>? =
        type.declaredConstructors.filter {
            val p = it.parameterTypes
            p.size == 2 &&
                p[0] == String::class.java &&
                p[1].isArray &&
                !p[1].componentType.isPrimitive
        }.singleOrNull()

    fun pooledFactory(type: Class<*>): Method? {
        val candidates = type.declaredMethods.filter { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) &&
                type.isAssignableFrom(method.returnType) &&
                p.size == 2 &&
                p[0] == String::class.java &&
                p[1] == Integer.TYPE
        }
        require(candidates.size <= 1) {
            "Room pooled query factory is structurally ambiguous"
        }
        return candidates.singleOrNull()
    }

    fun supported(type: Class<*>): Boolean =
        type.isInterface ||
            legacyConstructor(type) != null ||
            pooledFactory(type) != null
}
