package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Small semantic reflection helper for Playlist Link.
 *
 * Obfuscated GMMP names are accepted only as fast-path evidence. Every member
 * returned here must still satisfy the supplied structural predicate. Fallback
 * without a preferred name is allowed only when the structural candidate is
 * unique; ambiguity fails closed.
 */
internal object PlaylistBridgeReflectionResolver {
    fun loadClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        description: String,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*> {
        val rejected = arrayListOf<String>()
        candidateNames.distinct().forEach { name ->
            val type = runCatching { loader.loadClass(name) }.getOrNull()
                ?: return@forEach
            if (runCatching { predicate(type) }.getOrDefault(false)) {
                return type
            }
            rejected += name
        }
        throw IllegalStateException(
            "Could not resolve $description from ${candidateNames.joinToString()}" +
                if (rejected.isEmpty()) "" else " (shape rejected: ${rejected.joinToString()})"
        )
    }

    fun optionalClass(
        loader: ClassLoader,
        candidateNames: List<String>,
        predicate: (Class<*>) -> Boolean = { true }
    ): Class<*>? = candidateNames.distinct().firstNotNullOfOrNull { name ->
        runCatching { loader.loadClass(name) }.getOrNull()?.takeIf {
            runCatching { predicate(it) }.getOrDefault(false)
        }
    }

    fun methods(type: Class<*>): List<Method> {
        val out = linkedMapOf<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val key = method.name + "(" +
                    method.parameterTypes.joinToString(",") { it.name } + ")" +
                    ":" + method.returnType.name
                out.putIfAbsent(key, method)
            }
            current = current.superclass
        }
        type.methods.forEach { method ->
            val key = method.name + "(" +
                method.parameterTypes.joinToString(",") { it.name } + ")" +
                ":" + method.returnType.name
            out.putIfAbsent(key, method)
        }
        return out.values.toList()
    }

    fun fields(type: Class<*>): List<Field> {
        val out = linkedMapOf<String, Field>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                out.putIfAbsent(current.name + "#" + field.name, field)
            }
            current = current.superclass
        }
        return out.values.toList()
    }

    fun matchesDeclaredPresenterRuleAction(
        method: Method,
        presenterClass: Class<*>,
        baseRuleClass: Class<*>
    ): Boolean =
        method.declaringClass == presenterClass &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(baseRuleClass)) &&
            method.returnType == java.lang.Void.TYPE

    fun matchesStaticFieldSemanticValue(
        field: Field,
        valueClass: Class<*>,
        expectedValue: String
    ): Boolean {
        if (
            !java.lang.reflect.Modifier.isStatic(field.modifiers) ||
            !valueClass.isAssignableFrom(field.type)
        ) {
            return false
        }
        return runCatching {
            field.isAccessible = true
            field.get(null)?.toString() == expectedValue
        }.getOrDefault(false)
    }

    fun method(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Method) -> Boolean
    ): Method {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalMethod(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Method) -> Boolean
    ): Method? {
        val candidates = methods(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun field(
        type: Class<*>,
        preferredNames: List<String>,
        description: String,
        predicate: (Field) -> Boolean
    ): Field {
        val candidates = fields(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { it.name + ":" + it.type.name }
        }
        return candidates.single().apply { isAccessible = true }
    }

    fun optionalField(
        type: Class<*>,
        preferredNames: List<String>,
        predicate: (Field) -> Boolean
    ): Field? {
        val candidates = fields(type).filter(predicate)
        preferredNames.forEach { name ->
            candidates.filter { it.name == name }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
        }
        return candidates.singleOrNull()?.apply { isAccessible = true }
    }

    fun constructor(
        type: Class<*>,
        description: String,
        predicate: (Constructor<*>) -> Boolean
    ): Constructor<*> {
        val candidates = type.declaredConstructors.filter(predicate)
        require(candidates.size == 1) {
            "$description on ${type.name} is ambiguous/missing: " +
                candidates.joinToString { ctor ->
                    ctor.parameterTypes.joinToString(",", prefix = "(", postfix = ")") { it.name }
                }
        }
        return candidates.single().apply { isAccessible = true }
    }
}
