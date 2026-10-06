package io.github.alagga.gonesmart

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService

/**
 * Resolves the native Auto-DJ object from a live MusicService without relying
 * on an obfuscated accessor name.
 *
 * The accessor is invoked only after its DECLARED return type uniquely proves
 * the Auto-DJ shape. In addition to the executor/native-state structure, the
 * owner must expose GMMP's database boundary. This distinguishes the real
 * 4.2.1 qr owner from unrelated service helpers that happen to contain an
 * ExecutorService and integer state.
 */
internal object NativeAutoDjAccessorResolver {
    data class Resolution(
        val instance: Any,
        val accessor: Method
    )

    fun resolve(service: Any): Resolution? {
        val accessor = accessorCandidates(service.javaClass).singleOrNull()
            ?: return null
        accessor.isAccessible = true
        val instance = runCatching { accessor.invoke(service) }.getOrNull()
            ?: return null
        if (!looksLikeAutoDjType(instance.javaClass)) return null
        return Resolution(instance, accessor)
    }

    fun diagnosticShape(serviceType: Class<*>): String {
        val candidates = accessorCandidates(serviceType)
        return if (candidates.isEmpty()) {
            "none"
        } else {
            candidates.joinToString(",") { method ->
                method.declaringClass.name + "." + method.name +
                    "():" + method.returnType.name
            }
        }
    }

    internal fun accessorCandidates(serviceType: Class<*>): List<Method> =
        GmmpReflectionPolicy.callableMethods(serviceType)
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType != Void.TYPE &&
                    !method.returnType.isPrimitive &&
                    looksLikeAutoDjType(method.returnType)
            }
            .distinctBy { method ->
                method.declaringClass.name + "|" + method.name + "|" +
                    method.returnType.name
            }

    internal fun looksLikeAutoDjType(type: Class<*>): Boolean {
        val fields = instanceFields(type)
        val referenceFields = fields.filterNot { it.type.isPrimitive }
        if (referenceFields.size < 4) return false
        if (fields.none { field ->
                ExecutorService::class.java.isAssignableFrom(field.type)
            }) {
            return false
        }
        if (fields.none { field -> isDatabaseType(field.type) }) {
            return false
        }
        return referenceFields.any { field ->
            GmmpReflectionPolicy.callableMethods(field.type).any { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    !method.declaringClass.name.startsWith("java.") &&
                    !method.declaringClass.name.startsWith("android.") &&
                    !method.declaringClass.name.startsWith("kotlin.")
            }
        }
    }

    private fun isDatabaseType(type: Class<*>): Boolean {
        if (type.name == "gonemad.gmmp.data.database.GMDatabase") return true
        return generateSequence<Class<*>>(type) { it.superclass }.any {
            it.name == "androidx.room.RoomDatabase"
        }
    }

    private fun instanceFields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .toList()
}