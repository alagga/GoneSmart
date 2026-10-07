package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Historical compatibility guard for GMMP's Queue DAO array contracts.
 *
 * Device evidence from GMMP 4.2.1 proves that the nearest custom array family
 * (observed as ww3[]) is a native query predicate/where contract, not a
 * queue_table entity contract. Its constructor component is likewise a column
 * descriptor rather than an embedded Queue row.
 *
 * Therefore an array component can no longer nominate a mutation entity at
 * all. Real Queue rows must come from a read-only native DAO snapshot and then
 * pass exact live Cursor correlation, or from a generated Room adapter whose
 * own SQL explicitly proves queue_table ownership.
 */
internal object NativeQueueEntityTypeResolver {
    fun resolve(
        daoClass: Class<*>,
        methods: List<Method>
    ): Class<*>? {
        // Keep the bounded structural scan as a regression guard/documentation
        // point, but deliberately return no entity hint. A future GMMP build
        // must earn an entity mapping through stronger ownership evidence.
        val hierarchy = generateSequence<Class<*>>(daoClass) { it.superclass }
            .toList()
        methods.asSequence()
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 1 &&
                    it.parameterTypes.single().isArray
            }
            .mapNotNull { method ->
                val component = method.parameterTypes.single().componentType
                    ?: return@mapNotNull null
                if (!usable(component)) return@mapNotNull null
                val distance = hierarchy.indexOf(method.declaringClass)
                    .takeIf { it >= 0 } ?: Int.MAX_VALUE
                component to distance
            }
            .minByOrNull { it.second }

        return null
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
}
