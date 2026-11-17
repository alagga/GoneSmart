package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bounded read-only discovery of a possible GMMP CURRENT+1 source boundary.
 *
 * Accepted R8 names are optional fast paths, NEVER proof of semantics.
 * A renamed candidate is installed for passive observation only. The
 * caller must independently observe the candidate running naturally and
 * calling the corresponding source setter before it may be used for a
 * guarded refresh. Ambiguity disables only this optional repair.
 */
internal object NativeNextSourceBoundary {
    data class Binding(
        val refresh: Method,
        val setter: Method,
        val sourceType: Class<*>
    )

    fun resolve(serviceType: Class<*>, playerType: Class<*>): Binding? {
        val refreshCandidates = GmmpReflectionPolicy.concreteMethods(serviceType)
            .filter {
                Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == serviceType
            }
        val refresh = refreshCandidates
            .singleOrNull { it.name == "E2" }
            ?: refreshCandidates.singleOrNull()
            ?: return null

        val setterCandidates = GmmpReflectionPolicy.concreteMethods(playerType)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    it.parameterCount == 2 &&
                    !it.parameterTypes[0].isPrimitive &&
                    it.parameterTypes[0] != Any::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
        val setter = setterCandidates
            .singleOrNull { it.name == "h0" }
            ?: setterCandidates.singleOrNull()
            ?: return null
        return Binding(
            refresh = refresh.apply { isAccessible = true },
            setter = setter.apply { isAccessible = true },
            sourceType = setter.parameterTypes[0]
        )
    }
}
