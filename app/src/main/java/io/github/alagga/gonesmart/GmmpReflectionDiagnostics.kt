package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bounded, privacy-safe descriptions for GMMP compatibility diagnostics.
 * Never include argument values, library rows, titles or filesystem paths.
 */
internal object GmmpReflectionDiagnostics {
    fun methods(
        type: Class<*>,
        limit: Int = 24,
        predicate: (Method) -> Boolean = { true }
    ): String =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter(predicate)
            .distinctBy {
                it.declaringClass.name + "|" +
                    it.name + "|" +
                    it.parameterTypes.joinToString(",") { parameter ->
                        parameter.name
                    } + "|" +
                    it.returnType.name
            }
            .sortedWith(
                compareBy<Method>(
                    { it.name },
                    { it.declaringClass.name }
                )
            )
            .take(limit)
            .joinToString(",") {
                methodSignature(it)
            }
            .ifBlank { "none" }

    fun constructors(
        type: Class<*>,
        limit: Int = 12
    ): String =
        type.declaredConstructors
            .sortedBy { it.parameterTypes.size }
            .take(limit)
            .joinToString(",") {
                constructorSignature(it)
            }
            .ifBlank { "none" }

    private fun methodSignature(
        method: Method
    ): String =
        buildString {
            append(method.declaringClass.name)
            append(".")
            append(method.name)
            append("(")
            append(
                method.parameterTypes.joinToString(",") {
                    it.name
                }
            )
            append("):")
            append(method.returnType.name)
            if (Modifier.isAbstract(method.modifiers)) {
                append("[abstract]")
            }
            if (Modifier.isStatic(method.modifiers)) {
                append("[static]")
            }
        }

    private fun constructorSignature(
        constructor: Constructor<*>
    ): String =
        constructor.declaringClass.name +
            "(" +
            constructor.parameterTypes.joinToString(",") {
                it.name
            } +
            ")"
}
