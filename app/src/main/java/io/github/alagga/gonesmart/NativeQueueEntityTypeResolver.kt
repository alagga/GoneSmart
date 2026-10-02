package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Derives the concrete queue entity type from GMMP's generated DAO hierarchy.
 *
 * R8 erases the generated implementation writer to Object[], while the nearest
 * queue-specific superclass still exposes an array contract. GMMP 4.2.1 r25
 * proves that this nearest type can itself be a relation/wrapper (ww3) rather
 * than the queue_table writer entity. A wrapper may be unwrapped only when it
 * contains exactly one custom constructor type and that nested type has the
 * numeric shape of the four-column queue_table entity.
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
     * The observed 4.2.1 queue-specific array witness is ww3[], while ww3 has
     * constructor (pw3,String,Object). Do not pin either R8 name. Unwrap only
     * the unique non-platform constructor type when the wrapper itself is not
     * a four-number entity and the nested type is.
     */
    internal fun embeddedQueueEntity(witness: Class<*>): Class<*>? {
        if (looksLikeQueueEntity(witness)) return null
        val embedded = witness.declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }
            .filter(::usable)
            .filter { it != witness }
            .distinct()
        val candidate = embedded.singleOrNull() ?: return null
        return candidate.takeIf(::looksLikeQueueEntity)
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
