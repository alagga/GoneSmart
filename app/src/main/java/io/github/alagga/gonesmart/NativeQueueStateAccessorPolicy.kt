package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Selects a writable current-position boundary only on the same native state
 * host whose read signal already correlates with the live Queue Cursor.
 *
 * GMMP 4.2.1 repeatedly identifies the current queue position through
 * qr.t -> ur.method:b. A separate value-only scan can accidentally nominate
 * unrelated state (r34 did exactly that with dx3 while the pointer happened
 * to equal 1), so write discovery must never jump to another host after a
 * preferred host has produced read evidence.
 */
internal object NativeQueueStateAccessorPolicy {
    data class MethodSelection(
        val host: Any,
        val getter: Method,
        val setter: Method
    )

    data class Analysis(
        val host: Any,
        val matchingFields: List<Field>,
        val matchingGetters: List<Method>,
        val setters: List<Method>,
        val directSetters: List<Method>
    ) {
        val hasReadEvidence: Boolean
            get() = matchingFields.isNotEmpty() || matchingGetters.isNotEmpty()

        fun fieldSelection(): Field? =
            if (matchingGetters.isEmpty()) matchingFields.singleOrNull()
            else null

        fun methodSelection(): MethodSelection? {
            val directGetters = matchingGetters.filter {
                it.declaringClass == host.javaClass
            }
            val getter = directGetters.singleOrNull()
                ?: matchingGetters.singleOrNull()
                ?: return null
            val setter = directSetters.singleOrNull()
                ?: setters.singleOrNull()
                ?: return null
            getter.isAccessible = true
            setter.isAccessible = true
            return MethodSelection(host, getter, setter)
        }

        fun describe(currentValue: Int): String {
            fun Method.signature(): String =
                declaringClass.name + "." + name + "(" +
                    parameterTypes.joinToString(",") { it.name } +
                    "):" + returnType.name

            val fields = matchingFields.joinToString(",") {
                it.declaringClass.name + "." + it.name + ":" + it.type.name
            }.ifBlank { "none" }
            val getters = matchingGetters.joinToString(",") {
                it.signature()
            }.ifBlank { "none" }
            val allSetters = setters.joinToString(",") {
                it.signature()
            }.ifBlank { "none" }
            val ownedSetters = directSetters.joinToString(",") {
                it.signature()
            }.ifBlank { "none" }
            return "host=" + host.javaClass.name +
                " | current=" + currentValue +
                " | fields=" + fields +
                " | getters=" + getters +
                " | setters=" + allSetters +
                " | directSetters=" + ownedSetters
        }
    }

    fun analyze(host: Any, currentValue: Int): Analysis {
        val fields = hierarchyFields(host.javaClass)
            .filter {
                it.type == Integer.TYPE || it.type == Integer::class.java
            }
            .onEach { it.isAccessible = true }
            .filter { field ->
                runCatching {
                    (field.get(host) as? Number)?.toInt() == currentValue
                }.getOrDefault(false)
            }

        val methods = GmmpReflectionPolicy.callableMethods(host.javaClass)
            .filter { !Modifier.isStatic(it.modifiers) }
        val getters = methods.filter {
            it.parameterCount == 0 &&
                it.name != "hashCode" &&
                (
                    it.returnType == Integer.TYPE ||
                        it.returnType == Integer::class.java
                )
        }.filter { method ->
            runCatching {
                method.isAccessible = true
                (method.invoke(host) as? Number)?.toInt() == currentValue
            }.getOrDefault(false)
        }
        val setters = methods.filter {
            it.parameterCount == 1 &&
                (
                    it.parameterTypes[0] == Integer.TYPE ||
                        it.parameterTypes[0] == Integer::class.java
                ) &&
                it.returnType == java.lang.Void.TYPE
        }
        val directSetters = setters.filter {
            it.declaringClass == host.javaClass
        }

        return Analysis(
            host = host,
            matchingFields = fields,
            matchingGetters = getters,
            setters = setters,
            directSetters = directSetters
        )
    }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name
            }
            .toList()
}
