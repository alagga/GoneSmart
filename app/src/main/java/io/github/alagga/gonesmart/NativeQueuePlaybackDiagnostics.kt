package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only diagnostics for the still-open GMMP 4.2.1 playback-position
 * boundary. Nothing in this object invokes a writer. It only samples integer
 * state that GMMP already exposes and identifies natural methods worth
 * observing.
 */
internal object NativeQueuePlaybackDiagnostics {
    private const val MAX_HOSTS = 8
    private const val MAX_SIGNALS_PER_HOST = 12
    private const val MAX_ARGUMENT_FIELDS = 8

    data class Snapshot(val values: Map<String, Int>)

    fun snapshot(service: Any?, autoDj: Any?): Snapshot {
        val values = linkedMapOf<String, Int>()
        if (autoDj != null) {
            hierarchyFields(autoDj.javaClass)
                .asSequence()
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .mapNotNull { field ->
                    val host = runCatching {
                        field.isAccessible = true
                        field.get(autoDj)
                    }.getOrNull() ?: return@mapNotNull null
                    if (!eligibleHost(host)) return@mapNotNull null
                    field.name to host
                }
                .take(MAX_HOSTS)
                .forEach { (fieldName, host) ->
                    collectHostSignals(
                        host,
                        "autoDj.$fieldName->${host.javaClass.name}",
                        values
                    )
                }
        }
        if (service != null) {
            GmmpReflectionPolicy.concreteMethods(service.javaClass)
                .asSequence()
                .filter { method ->
                    method.declaringClass == service.javaClass &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        (method.returnType == Integer.TYPE ||
                            method.returnType == Integer::class.java) &&
                        method.name != "hashCode"
                }
                .take(MAX_SIGNALS_PER_HOST)
                .forEach { method ->
                    val value = runCatching {
                        method.isAccessible = true
                        (method.invoke(service) as? Number)?.toInt()
                    }.getOrNull() ?: return@forEach
                    values["service.${method.name}()"] = value
                }
        }
        return Snapshot(values)
    }

    fun changes(before: Snapshot, after: Snapshot): List<String> {
        val keys = LinkedHashSet<String>()
        keys += before.values.keys
        keys += after.values.keys
        return keys.mapNotNull { key ->
            val old = before.values[key]
            val new = after.values[key]
            if (old == null || new == null || old == new) null
            else "$key:$old->$new"
        }
    }

    /**
     * Direct MusicService methods that are safe to observe passively. The
     * hook never calls these methods itself; it only wraps invocations GMMP
     * makes naturally. We deliberately include zero-arg and event-style
     * methods because the 4.2.1 title transition bypasses all one-Int service
     * commands observed in r39.
     */
    fun playbackMethods(serviceClass: Class<*>): List<Method> =
        GmmpReflectionPolicy.concreteMethods(serviceClass)
            .filter { method ->
                method.declaringClass == serviceClass &&
                    !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers) &&
                    method.parameterCount <= 2 &&
                    (method.returnType == Void.TYPE ||
                        method.returnType == Boolean::class.javaPrimitiveType ||
                        method.returnType == Boolean::class.java) &&
                    method.name !in setOf(
                        "onCreate",
                        "onDestroy",
                        "onBind"
                    )
            }
            .distinctBy(::signature)
            .take(48)

    /**
     * Candidate state-host writers are derived from Auto-DJ's declared child
     * types. They are only hooked, never invoked by discovery. A type must
     * expose integer read state and a one-Int/void command before it is
     * considered. This recovers the old qr.p/dx3 surface without making the
     * obfuscated field name its semantic identity.
     */
    fun stateWriterMethods(autoDjClass: Class<*>): List<Method> {
        val hostTypes = hierarchyFields(autoDjClass)
            .asSequence()
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.type }
            .filter(::eligibleDeclaredType)
            .distinct()
            .filter { type ->
                val hasIntegerRead = hierarchyFields(type).any { field ->
                    !Modifier.isStatic(field.modifiers) &&
                        (field.type == Integer.TYPE || field.type == Integer::class.java)
                } || GmmpReflectionPolicy.concreteMethods(type).any { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        (method.returnType == Integer.TYPE ||
                            method.returnType == Integer::class.java)
                }
                val hasWriter = GmmpReflectionPolicy.concreteMethods(type).any { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterCount == 1 &&
                        (method.parameterTypes[0] == Integer.TYPE ||
                            method.parameterTypes[0] == Integer::class.java)
                }
                hasIntegerRead && hasWriter
            }
            .take(MAX_HOSTS)
            .toSet()

        return hostTypes.flatMap { type ->
            GmmpReflectionPolicy.concreteMethods(type).filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterCount == 1 &&
                    (method.parameterTypes[0] == Integer.TYPE ||
                        method.parameterTypes[0] == Integer::class.java)
            }
        }.distinctBy(::signature).take(24)
    }

    fun describeArguments(args: List<Any?>): String =
        args.mapIndexed { index, value ->
            "arg$index=" + describeArgument(value)
        }.joinToString(",").ifBlank { "none" }

    fun signatures(methods: List<Method>): String =
        methods.joinToString(",") { signature(it) }

    private fun collectHostSignals(
        host: Any,
        prefix: String,
        target: MutableMap<String, Int>
    ) {
        var count = 0
        hierarchyFields(host.javaClass).forEach { field ->
            if (count >= MAX_SIGNALS_PER_HOST) return@forEach
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic ||
                (field.type != Integer.TYPE && field.type != Integer::class.java)
            ) return@forEach
            val value = runCatching {
                field.isAccessible = true
                (field.get(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            target["$prefix.field:${field.name}"] = value
            count++
        }
        GmmpReflectionPolicy.concreteMethods(host.javaClass).forEach { method ->
            if (count >= MAX_SIGNALS_PER_HOST) return@forEach
            if (Modifier.isStatic(method.modifiers) ||
                method.parameterCount != 0 ||
                (method.returnType != Integer.TYPE &&
                    method.returnType != Integer::class.java) ||
                method.name == "hashCode"
            ) return@forEach
            val value = runCatching {
                method.isAccessible = true
                (method.invoke(host) as? Number)?.toInt()
            }.getOrNull() ?: return@forEach
            target["$prefix.method:${method.name}()"] = value
            count++
        }
    }

    private fun describeArgument(value: Any?): String {
        if (value == null) return "null"
        if (value is Number || value is Boolean || value is Char) {
            return "${value.javaClass.simpleName}($value)"
        }
        if (value.javaClass.isEnum) return value.javaClass.name
        val fields = hierarchyFields(value.javaClass)
            .asSequence()
            .filter { field ->
                !Modifier.isStatic(field.modifiers) && !field.isSynthetic &&
                    (field.type.isPrimitive ||
                        Number::class.java.isAssignableFrom(field.type) ||
                        field.type == Boolean::class.java)
            }
            .take(MAX_ARGUMENT_FIELDS)
            .mapNotNull { field ->
                val fieldValue = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull() ?: return@mapNotNull null
                "${field.name}=$fieldValue"
            }
            .toList()
        return value.javaClass.name +
            if (fields.isEmpty()) "" else fields.joinToString(",", "{", "}")
    }

    private fun eligibleHost(value: Any): Boolean =
        eligibleDeclaredType(value.javaClass) &&
            value !is Collection<*> &&
            value !is java.util.concurrent.Executor &&
            hierarchyFields(value.javaClass).none { field ->
                field.type.name == "gonemad.gmmp.data.database.GMDatabase" ||
                    field.type.name.startsWith("androidx.room.")
            }

    private fun eligibleDeclaredType(type: Class<*>): Boolean {
        val name = type.name
        return !type.isPrimitive &&
            !name.startsWith("java.") &&
            !name.startsWith("android.") &&
            !name.startsWith("androidx.") &&
            !name.startsWith("kotlin.") &&
            !java.util.concurrent.Executor::class.java.isAssignableFrom(type) &&
            !Collection::class.java.isAssignableFrom(type)
    }

    private fun signature(method: Method): String =
        method.declaringClass.name + "." + method.name + "(" +
            method.parameterTypes.joinToString(",") { it.name } + ")" +
            ":" + method.returnType.name

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .toList()
}
