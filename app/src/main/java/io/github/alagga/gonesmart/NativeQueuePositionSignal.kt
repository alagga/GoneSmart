package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only playback-position signal used to prove a Queue writer.
 *
 * r39 device evidence disproved qr.t -> ur.b() as queue_position: ur.b()
 * remained 1 while GMMP was actively reading queue positions 7 and 8. The
 * earlier 4.2.1 runtime mapping qr.p -> dx3.field:o had repeatedly correlated
 * with the actually playing queue row, so that owner is restored here.
 *
 * The observed field name is only a verified-version fast path. If it is not
 * present, discovery fails closed unless the same host exposes exactly one
 * integer read signal. Other Auto-DJ children are deliberately not searched;
 * value-only cross-host correlation caused the ur/dx3 false positives.
 */
internal object NativeQueuePositionSignal {
    data class Reading(val value: Int, val source: String)

    fun read(autoDj: Any): Reading? {
        val host = readField(autoDj, "p") ?: return null
        if (!eligibleHost(host)) return null
        return readHost(host, "field:p->${host.javaClass.name}")
    }

    private fun readHost(host: Any, source: String): Reading? {
        hierarchyFields(host.javaClass)
            .firstOrNull { field ->
                field.name == "o" &&
                    !Modifier.isStatic(field.modifiers) &&
                    (field.type == Integer.TYPE || field.type == Integer::class.java)
            }
            ?.let { field ->
                val value = runCatching {
                    field.isAccessible = true
                    (field.get(host) as? Number)?.toInt()
                }.getOrNull()
                if (value != null) {
                    return Reading(value, "$source.field:${field.name}")
                }
            }

        val signals = arrayListOf<Reading>()
        hierarchyFields(host.javaClass).forEach { field ->
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic ||
                (field.type != Integer.TYPE && field.type != Integer::class.java)
            ) return@forEach
            val value = runCatching {
                field.isAccessible = true
                (field.get(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            signals += Reading(value, "$source.field:${field.name}")
        }
        hierarchyMethods(host.javaClass).forEach { method ->
            if (Modifier.isStatic(method.modifiers) ||
                Modifier.isAbstract(method.modifiers) ||
                method.parameterCount != 0 ||
                (method.returnType != Integer.TYPE &&
                    method.returnType != Integer::class.java) ||
                method.name == "hashCode"
            ) return@forEach
            val value = runCatching {
                method.isAccessible = true
                (method.invoke(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            signals += Reading(value, "$source.method:${method.name}")
        }

        val distinctValues = signals.map { it.value }.distinct()
        if (distinctValues.size != 1) return null
        val value = distinctValues.single()
        val sources = signals.filter { it.value == value }.joinToString("+") {
            it.source.substringAfter("$source.")
        }
        return Reading(value, "$source.$sources")
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
