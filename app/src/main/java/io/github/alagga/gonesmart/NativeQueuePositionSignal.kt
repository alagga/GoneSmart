package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only playback-position signal used by Queue Flip.
 *
 * GMMP 4.2.1 exposes the current queue_position through the state object
 * owned by Auto-DJ (device-proven as qr.t -> ur.b()). Names are only a fast
 * path: a fallback is accepted only when exactly one custom state host has
 * exactly one concrete zero-arg Int getter.
 */
internal object NativeQueuePositionSignal {
    data class Reading(val value: Int, val source: String)

    fun read(autoDj: Any): Reading? {
        readField(autoDj, "t")?.let { host ->
            readHost(host, "field:t->${host.javaClass.name}")?.let { return it }
        }

        val candidates = hierarchyFields(autoDj.javaClass)
            .mapNotNull { field ->
                val host = runCatching {
                    field.isAccessible = true
                    field.get(autoDj)
                }.getOrNull() ?: return@mapNotNull null
                if (!eligibleHost(host)) return@mapNotNull null
                readHost(host, "field:${field.name}->${host.javaClass.name}")
            }
        val unique = candidates.distinctBy { it.value to it.source }
        return unique.singleOrNull()
    }

    private fun readHost(host: Any, source: String): Reading? {
        val getters = hierarchyMethods(host.javaClass).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                method.parameterCount == 0 &&
                (method.returnType == Integer.TYPE ||
                    method.returnType == Integer::class.java) &&
                method.name != "hashCode"
        }.distinctBy {
            it.declaringClass.name + "|" + it.name + "|" + it.returnType.name
        }
        val getter = getters.singleOrNull() ?: return null
        val value = runCatching {
            getter.isAccessible = true
            (getter.invoke(host) as? Number)?.toInt()
        }.getOrNull() ?: return null
        return Reading(value, "$source.method:${getter.name}")
    }

    private fun eligibleHost(value: Any): Boolean {
        val name = value.javaClass.name
        if (name.startsWith("java.") ||
            name.startsWith("android.") ||
            name.startsWith("androidx.") ||
            name.startsWith("kotlin.") ||
            value is Collection<*> ||
            value is java.util.concurrent.Executor
        ) return false
        return hierarchyFields(value.javaClass).none { field ->
            field.type.name == "gonemad.gmmp.data.database.GMDatabase" ||
                field.type.name.startsWith("androidx.room.")
        }
    }

    private fun readField(target: Any, name: String): Any? =
        hierarchyFields(target.javaClass).firstOrNull { it.name == name }?.let {
            runCatching {
                it.isAccessible = true
                it.get(target)
            }.getOrNull()
        }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .toList()

    private fun hierarchyMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .toList()
}
