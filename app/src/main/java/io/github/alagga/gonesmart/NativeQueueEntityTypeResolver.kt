package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Derives the concrete queue entity type from GMMP's generated DAO hierarchy.
 *
 * R8 erases the generated implementation writer to Object[], but the nearest
 * queue-specific superclass still exposes array contracts such as ww3[].
 * Generic base-DAO array contracts live farther up the hierarchy and therefore
 * must not win merely because they are also callable.
 */
internal object NativeQueueEntityTypeResolver {
    fun resolve(
        daoClass: Class<*>,
        methods: List<Method>
    ): Class<*>? {
        val hierarchy = generateSequence<Class<*>>(daoClass) { it.superclass }
            .toList()
        val candidates = methods.mapNotNull { method ->
            if (Modifier.isStatic(method.modifiers) ||
                method.parameterCount != 1
            ) return@mapNotNull null
            val array = method.parameterTypes.single()
            if (!array.isArray) return@mapNotNull null
            val component = array.componentType ?: return@mapNotNull null
            if (component.isPrimitive ||
                component == Any::class.java ||
                component.name.startsWith("java.") ||
                component.name.startsWith("android.") ||
                component.name.startsWith("kotlin.")
            ) return@mapNotNull null

            val distance = hierarchy.indexOf(method.declaringClass)
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
            Candidate(component, distance)
        }
        if (candidates.isEmpty()) return null

        val bestDistance = candidates.minOf { it.distance }
        val nearest = candidates
            .filter { it.distance == bestDistance }
            .map { it.type }
            .distinct()
        return nearest.singleOrNull()
    }

    private data class Candidate(
        val type: Class<*>,
        val distance: Int
    )
}
