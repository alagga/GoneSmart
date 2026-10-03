package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the nearest custom array contract in GMMP's Queue DAO hierarchy.
 *
 * Historical name note: older GoneSmart revisions treated this array component
 * as a possible Queue entity type. GMMP 4.2.1 device evidence now shows that
 * the observed custom array is the native where/predicate family instead. The
 * returned class is therefore only a read-boundary witness used to reach
 * GMMP's own predicate-based List reader. It is never accepted as writer
 * entity ownership. The mutation bridge still requires exact live Cursor
 * correlation of queue_id, song_id and queue_position before any writer runs.
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
            if (!usable(component)) return@mapNotNull null

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

        // r29: do not unwrap constructor components of the array type. The
        // 4.2.1 witness has the shape Predicate(Column, operator, value), so
        // its custom constructor component is a column descriptor, not a
        // queue_table entity. The witness itself is enough to identify the
        // native predicate-list reader structurally.
        return nearest.singleOrNull()
    }

    private fun usable(type: Class<*>): Boolean {
        val name = type.name
        return !type.isPrimitive &&
            !type.isArray &&
            type != Any::class.java &&
            type != String::class.java &&
            !name.startsWith("java.") &&
            !name.startsWith("android.") &&
            !name.startsWith("kotlin.")
    }

    private data class Candidate(
        val type: Class<*>,
        val distance: Int
    )
}
