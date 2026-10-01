package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Bounded, privacy-safe descriptions for GMMP compatibility diagnostics.
 * Never include argument values, library rows, titles or filesystem paths.
 */
internal object GmmpReflectionDiagnostics {

    fun hierarchy(
        type: Class<*>,
        limit: Int = 8
    ): String =
        generateSequence<Class<*>>(type) { it.superclass }
            .take(limit)
            .joinToString(">") { it.name }
            .ifBlank { "none" }

    fun interfaces(
        type: Class<*>,
        limit: Int = 16
    ): String =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .distinctBy { it.name }
            .take(limit)
            .joinToString(",") { it.name }
            .ifBlank { "none" }

    fun runtimeFieldTypes(
        instance: Any,
        limit: Int = 24
    ): String =
        generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .take(limit)
            .joinToString(",") { field ->
                val runtimeType =
                    runCatching {
                        field.isAccessible = true
                        field.get(instance)?.javaClass?.name ?: "null"
                    }.getOrElse {
                        "<unreadable>"
                    }
                field.declaringClass.name +
                    "." +
                    field.name +
                    ":" +
                    field.type.name +
                    "->" +
                    runtimeType
            }
            .ifBlank { "none" }

    fun fields(
        type: Class<*>,
        limit: Int = 24
    ): String =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .sortedWith(
                compareBy<java.lang.reflect.Field>(
                    { it.name },
                    { it.declaringClass.name }
                )
            )
            .take(limit)
            .joinToString(",") {
                buildString {
                    append(it.declaringClass.name)
                    append(".")
                    append(it.name)
                    append(":")
                    append(it.type.name)
                    if (Modifier.isStatic(it.modifiers)) {
                        append("[static]")
                    }
                }
            }
            .ifBlank { "none" }

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
