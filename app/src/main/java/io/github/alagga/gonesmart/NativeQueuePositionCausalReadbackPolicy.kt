package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Proves the read side of a naturally observed native Int writer without
 * relying on an obfuscated name.
 *
 * Discovery is read-only: integer fields and zero-arg integer methods on the
 * SAME receiver are sampled before and after GMMP invokes the writer itself.
 * A readback is accepted only when it changes to exactly the writer argument.
 * Direct zero-arg getters are preferred over fields because they preserve the
 * native ownership boundary. Ambiguity at any preference level fails closed.
 */
internal object NativeQueuePositionCausalReadbackPolicy {
    private enum class Kind { METHOD, FIELD }

    private data class Key(
        val kind: Kind,
        val owner: Class<*>,
        val name: String
    )

    data class Snapshot internal constructor(
        internal val values: Map<Any, Int>
    )

    class Readback internal constructor(
        private val hostClass: Class<*>,
        private val method: Method?,
        private val field: Field?
    ) {
        val description: String = when {
            method != null ->
                method.declaringClass.name + ".method:" + method.name
            field != null ->
                field.declaringClass.name + ".field:" + field.name
            else -> error("Native queue readback has no accessor")
        }

        fun read(host: Any): Int? {
            if (!hostClass.isInstance(host)) return null
            return runCatching {
                when {
                    method != null -> {
                        method.isAccessible = true
                        (method.invoke(host) as? Number)?.toInt()
                    }
                    field != null -> {
                        field.isAccessible = true
                        (field.get(host) as? Number)?.toInt()
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }

    fun snapshot(host: Any): Snapshot {
        val values = linkedMapOf<Any, Int>()
        candidateMethods(host.javaClass).forEach { method ->
            val value = runCatching {
                method.isAccessible = true
                (method.invoke(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            values[Key(Kind.METHOD, method.declaringClass, method.name)] = value
        }
        candidateFields(host.javaClass).forEach { field ->
            val value = runCatching {
                field.isAccessible = true
                (field.get(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            values[Key(Kind.FIELD, field.declaringClass, field.name)] = value
        }
        return Snapshot(values)
    }

    fun select(
        host: Any,
        before: Snapshot,
        writerArgument: Int
    ): Readback? {
        val afterMethods = candidateMethods(host.javaClass).mapNotNull { method ->
            val key = Key(Kind.METHOD, method.declaringClass, method.name)
            val old = before.values[key] ?: return@mapNotNull null
            val value = runCatching {
                method.isAccessible = true
                (method.invoke(host) as? Number)?.toInt()
            }.getOrNull() ?: return@mapNotNull null
            if (old != value && value == writerArgument) method else null
        }
        val afterFields = candidateFields(host.javaClass).mapNotNull { field ->
            val key = Key(Kind.FIELD, field.declaringClass, field.name)
            val old = before.values[key] ?: return@mapNotNull null
            val value = runCatching {
                field.isAccessible = true
                (field.get(host) as? Number)?.toInt()
            }.getOrNull() ?: return@mapNotNull null
            if (old != value && value == writerArgument) field else null
        }

        val directMethods = afterMethods.filter {
            it.declaringClass == host.javaClass
        }
        directMethods.singleOrNull()?.let {
            return Readback(host.javaClass, it, null)
        }
        if (directMethods.size > 1) return null

        val directFields = afterFields.filter {
            it.declaringClass == host.javaClass
        }
        directFields.singleOrNull()?.let {
            return Readback(host.javaClass, null, it)
        }
        if (directFields.size > 1) return null

        afterMethods.singleOrNull()?.let {
            return Readback(host.javaClass, it, null)
        }
        if (afterMethods.size > 1) return null

        return afterFields.singleOrNull()?.let {
            Readback(host.javaClass, null, it)
        }
    }

    private fun candidateMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers) &&
                    !method.isSynthetic &&
                    method.parameterCount == 0 &&
                    (method.returnType == Integer.TYPE ||
                        method.returnType == Integer::class.java) &&
                    method.name !in setOf("hashCode", "identityHashCode") &&
                    !method.declaringClass.name.startsWith("java.") &&
                    !method.declaringClass.name.startsWith("android.") &&
                    !method.declaringClass.name.startsWith("kotlin.")
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.returnType.name
            }
            .toList()

    private fun candidateFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { field ->
                !Modifier.isStatic(field.modifiers) &&
                    !field.isSynthetic &&
                    (field.type == Integer.TYPE ||
                        field.type == Integer::class.java) &&
                    !field.declaringClass.name.startsWith("java.") &&
                    !field.declaringClass.name.startsWith("android.") &&
                    !field.declaringClass.name.startsWith("kotlin.")
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name
            }
            .toList()
}