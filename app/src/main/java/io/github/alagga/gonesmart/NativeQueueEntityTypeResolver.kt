package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Derives the concrete queue entity type from GMMP's generated DAO hierarchy.
 *
 * R8 erases the generated implementation writer to Object[], while the nearest
 * queue-specific superclass can still expose a relation/wrapper array contract.
 * The wrapper is only a type witness: when it has exactly one non-platform
 * constructor component, that nested type can be nominated for read-carrier
 * unwrapping even when R8 has obscured its field/constructor shape. The caller
 * still requires exact live Cursor correlation of queue_id, song_id and
 * queue_position before any writer becomes eligible.
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
        val witness = nearest.singleOrNull() ?: return null
        return embeddedQueueEntity(witness) ?: witness
    }

    /**
     * The observed 4.2.1 queue-specific array witness is a relation wrapper.
     * Do not pin its R8 name or the nested model name. If the wrapper itself
     * already has a direct Queue-entity numeric shape, keep it. Otherwise a
     * unique custom constructor component is a safe *read-only nomination*:
     * NativeReactiveListReader must find exactly one such nested instance in
     * each emitted relation row, and GmmpQueueMutationBridge must then prove
     * the complete set one-to-one against the live Queue Cursor before any
     * mutation method can be selected or invoked.
     *
     * Requiring the embedded type to expose four obvious numeric fields here
     * was too strict after R8: the r24 host pass consequently stopped at the
     * wrapper witness and never exercised the stronger runtime correlation.
     */
    internal fun embeddedQueueEntity(witness: Class<*>): Class<*>? {
        if (looksLikeQueueEntity(witness)) return null
        val embedded = witness.declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }
            .filter(::usable)
            .filter { it != witness }
            .distinct()
        return embedded.singleOrNull()
    }

    private fun looksLikeQueueEntity(type: Class<*>): Boolean {
        val numericFields = generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .count {
                !Modifier.isStatic(it.modifiers) &&
                    !it.isSynthetic &&
                    isNumeric(it.type)
            }
        if (numericFields >= 4) return true
        return type.declaredConstructors.any { ctor ->
            ctor.parameterCount == 4 &&
                ctor.parameterTypes.all(::isNumeric)
        }
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

    private fun isNumeric(type: Class<*>): Boolean =
        type == Integer.TYPE || type == Integer::class.java ||
            type == java.lang.Long.TYPE || type == java.lang.Long::class.java ||
            type == java.lang.Short.TYPE || type == java.lang.Short::class.java

    private data class Candidate(
        val type: Class<*>,
        val distance: Int
    )
}
