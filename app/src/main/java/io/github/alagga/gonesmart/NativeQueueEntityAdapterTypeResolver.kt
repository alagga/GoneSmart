package io.github.alagga.gonesmart

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type

/**
 * Resolves the actual Room queue entity from GMMP's generated DAO adapters.
 *
 * GMMP 4.2.1 keeps queue-specific API contracts on y75 that mention ww3, but
 * device evidence proves ww3 is not the queue_table writer entity. The
 * generated d85 adapter fields are a stronger ownership boundary: their SQL
 * names queue_table and their erased bind(Object) bridge must cast that Object
 * to the real entity before it can read fields.
 *
 * This resolver is read-only. It calls only the adapter SQL-string method and
 * the binder callback against a fake binder with a deliberately wrong marker
 * object. No SQLite statement is created or executed.
 */
internal object NativeQueueEntityAdapterTypeResolver {
    data class Result(
        val modelClass: Class<*>,
        val evidence: String
    )

    fun resolve(dao: Any): Result? {
        val candidates = hierarchyFields(dao.javaClass).mapNotNull { field ->
            field.isAccessible = true
            val adapter = runCatching { field.get(dao) }.getOrNull()
                ?: return@mapNotNull null
            resolveAdapter(adapter)?.let { result ->
                field.name to result
            }
        }
        if (candidates.isEmpty()) return null

        val classes = candidates.map { it.second.modelClass }.distinct()
        if (classes.size != 1) return null
        val model = classes.single()
        val evidence = candidates
            .filter { it.second.modelClass == model }
            .joinToString("+") { (field, result) ->
                field + "->" + result.evidence
            }
        return Result(model, evidence)
    }

    private fun resolveAdapter(adapter: Any): Result? {
        val methods = GmmpReflectionPolicy.callableMethods(adapter.javaClass)
            .filter { !Modifier.isStatic(it.modifiers) }
        val sql = methods.asSequence()
            .filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }
            .mapNotNull { method ->
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(adapter) as? String
                }.getOrNull() ?: return@mapNotNull null
                value.takeIf {
                    it.contains("queue_table", ignoreCase = true)
                }?.let { method to it }
            }
            .firstOrNull() ?: return null

        val binders = methods.filter {
            it.parameterCount == 2 &&
                it.parameterTypes[0].isInterface &&
                it.returnType == java.lang.Void.TYPE &&
                it.parameterTypes[1] == Any::class.java
        }
        val binder = binders.singleOrNull() ?: return null

        val loader = adapter.javaClass.classLoader
        inferGenericEntity(adapter.javaClass)?.let { type ->
            if (usable(type)) {
                return Result(
                    type,
                    adapter.javaClass.name + "." + sql.first.name +
                        ":generic"
                )
            }
        }

        val binderType = binder.parameterTypes[0]
        val fakeBinder = Proxy.newProxyInstance(
            binderType.classLoader ?: loader,
            arrayOf(binderType)
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "GoneSmart queue adapter type probe"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        }

        val failure = runCatching {
            binder.isAccessible = true
            binder.invoke(adapter, fakeBinder, TypeProbeMarker)
            null
        }.exceptionOrNull() ?: return null
        val target = classCastTarget(failure, loader) ?: return null
        if (!usable(target)) return null
        return Result(
            target,
            adapter.javaClass.name + "." + sql.first.name +
                ":binder-cast"
        )
    }

    private fun inferGenericEntity(type: Class<*>): Class<*>? {
        val types = buildList<Type> {
            add(type.genericSuperclass)
            addAll(type.genericInterfaces)
        }
        val classes = types.flatMap(::classesFromType)
            .filter(::usable)
            .distinct()
        return classes.singleOrNull()
    }

    private fun classesFromType(type: Type?): List<Class<*>> = when (type) {
        is Class<*> -> emptyList()
        is ParameterizedType -> type.actualTypeArguments.mapNotNull {
            when (it) {
                is Class<*> -> it
                is ParameterizedType -> it.rawType as? Class<*>
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun classCastTarget(
        throwable: Throwable,
        loader: ClassLoader?
    ): Class<*>? {
        val cast = generateSequence(throwable) { error ->
            when (error) {
                is InvocationTargetException ->
                    error.targetException ?: error.cause
                else -> error.cause
            }
        }.firstOrNull { it is ClassCastException } ?: return null
        val message = cast.message ?: return null

        val patterns = listOf(
            Regex("""cannot be cast to class ([A-Za-z0-9_.$]+)"""),
            Regex("""cannot be cast to ([A-Za-z0-9_.$]+)""")
        )
        val name = patterns.firstNotNullOfOrNull { regex ->
            regex.find(message)?.groupValues?.getOrNull(1)
        } ?: return null
        return runCatching {
            (loader ?: ClassLoader.getSystemClassLoader()).loadClass(name)
        }.getOrNull()
    }

    private fun usable(type: Class<*>): Boolean {
        val name = type.name
        return type != Any::class.java &&
            !type.isInterface &&
            !type.isPrimitive &&
            !name.startsWith("java.") &&
            !name.startsWith("android.") &&
            !name.startsWith("kotlin.")
    }

    private fun hierarchyFields(type: Class<*>) =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .toList()

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Void.TYPE -> null
        else -> null
    }

    private object TypeProbeMarker
}
