package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Small reflection policy shared by compatibility resolvers.
 *
 * Obfuscated member names are allowed as a verified-version fast path, but
 * fallback discovery must be structural and unique. Abstract declarations are
 * contracts, not callable implementations.
 */
internal object GmmpReflectionPolicy {
    fun concreteMethods(
        type: Class<*>
    ): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                !Modifier.isAbstract(it.modifiers)
            }
            .distinctBy {
                it.name + "|" +
                    it.parameterTypes.joinToString(",") { parameter ->
                        parameter.name
                    } + "|" +
                    it.returnType.name
            }
            .toList()

    fun uniqueConcreteMethod(
        type: Class<*>,
        predicate: (Method) -> Boolean
    ): Method? =
        concreteMethods(type)
            .filter(predicate)
            .singleOrNull()
}
