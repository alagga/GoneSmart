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

    /**
     * Callable methods visible on a runtime type, including inherited
     * interface contracts. This is intentionally broader than
     * [concreteMethods]: Java reflection can invoke an interface Method on
     * an implementing object, and GMMP 4.2.1 exposes Room boundaries such as
     * f94.q(p94):Cursor through exactly that shape.
     */
    fun callableMethods(
        type: Class<*>
    ): List<Method> {
        val declared = generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
        val inherited = type.methods.asSequence()
        return (declared + inherited)
            .filter { !it.isSynthetic }
            .distinctBy {
                it.name + "|" +
                    it.parameterTypes.joinToString(",") { parameter ->
                        parameter.name
                    } + "|" +
                    it.returnType.name
            }
            .toList()
    }

    fun uniqueConcreteMethod(
        type: Class<*>,
        predicate: (Method) -> Boolean
    ): Method? =
        concreteMethods(type)
            .filter(predicate)
            .singleOrNull()
}
