package io.github.alagga.gonesmart

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type

/**
 * Resolves the actual Room queue entity from GMMP's generated DAO adapters.
 *
 * GMMP 4.2.1 keeps queue-specific API contracts on y75 that mention ww3, but
 * device evidence proves ww3 is not the queue_table writer entity. The
 * generated d85 adapter fields are a stronger ownership boundary: their
 * erased bind(statement,Object) bridge must cast that Object to the real
 * entity before it can read fields.
 *
 * This resolver is read-only. It calls only the adapter SQL-string method and
 * the binder callback with a deliberately wrong marker object. For an
 * interface statement contract it supplies a no-op proxy; for a concrete
 * host statement contract (GMMP 4.2.1: yb4) it supplies null. Generated Room
 * bridges cast the entity argument before binding it, so the resulting
 * ClassCastException exposes the real model class without creating/executing
 * a SQLite statement.
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
            resolveAdapter(
                adapter = adapter,
                directlyOwnedByDao = field.declaringClass == dao.javaClass
            )?.let { result ->
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

    private fun resolveAdapter(
        adapter: Any,
        directlyOwnedByDao: Boolean
    ): Result? {
        // Room's generic EntityInsertion/Deletion/Update adapters expose the
        // erased bind(statement,Object) boundary as a compiler bridge. On the
        // tested GMMP 4.2.1 build that bridge is synthetic. The general
        // GmmpReflectionPolicy deliberately excludes synthetic methods, which
        // is correct for broad host discovery but wrong at this already-owned
        // generated Room adapter boundary. Include bridges locally here only.
        val methods = generatedAdapterMethods(adapter.javaClass)
            .filter { !Modifier.isStatic(it.modifiers) }
        val stringMethods = methods.filter {
            it.parameterCount == 0 &&
                it.returnType == String::class.java &&
                it.declaringClass != Any::class.java
        }
        val sqlValues = stringMethods.mapNotNull { method ->
            runCatching {
                method.isAccessible = true
                (method.invoke(adapter) as? String)
                    ?.let { method to it }
            }.getOrNull()
        }
        val queueSql = sqlValues.firstOrNull { (_, value) ->
            value.contains("queue_table", ignoreCase = true)
        }
        val dmlSql = sqlValues.firstOrNull { (_, value) ->
            val normalized = value.trimStart().uppercase()
            normalized.startsWith("INSERT") ||
                normalized.startsWith("UPDATE") ||
                normalized.startsWith("DELETE")
        }

        // Do not assume SupportSQLiteStatement remains an interface after
        // host/R8 rewriting. GMMP 4.2.1 exposes G(yb4,Object):void on the
        // generated d85 adapter fields. The first parameter is opaque here;
        // only a reference type is required because no statement is executed.
        val binders = methods.filter {
            it.parameterCount == 2 &&
                !it.parameterTypes[0].isPrimitive &&
                it.returnType == java.lang.Void.TYPE &&
                it.parameterTypes[1] == Any::class.java
        }
        val binder = binders.singleOrNull() ?: return null

        // The SQL text is the strongest proof. Keep a second structural proof
        // for the already-verified generated d85 fields: one adapter-local
        // String boundary plus one erased bind(statement,Object) bridge. All
        // participating adapters must still agree on one entity class.
        val ownership = when {
            queueSql != null -> "queue-sql"
            directlyOwnedByDao && dmlSql != null -> "owned-dml"
            directlyOwnedByDao &&
                stringMethods.size == 1 &&
                stringMethods.single().declaringClass == adapter.javaClass ->
                "owned-adapter-shape"
            else -> return null
        }
        val sqlMethodName =
            (queueSql ?: dmlSql)?.first?.name
                ?: stringMethods.single().name

        val loader = adapter.javaClass.classLoader
        inferGenericEntity(adapter.javaClass)?.let { type ->
            if (usable(type)) {
                return Result(
                    type,
                    adapter.javaClass.name + "." + sqlMethodName +
                        ":generic:" + ownership
                )
            }
        }

        val statementType = binder.parameterTypes[0]
        val statement = if (statementType.isInterface) {
            Proxy.newProxyInstance(
                statementType.classLoader ?: loader,
                arrayOf(statementType)
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "GoneSmart queue adapter type probe"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> primitiveDefault(method.returnType)
                }
            }
        } else {
            // Cast-only discovery path for the concrete yb4 contract. A real
            // statement must never be constructed just to discover a type.
            null
        }

        val failure = runCatching {
            binder.isAccessible = true
            binder.invoke(adapter, statement, TypeProbeMarker)
            null
        }.exceptionOrNull() ?: return null
        val target = classCastTarget(failure, loader) ?: return null
        if (!usable(target)) return null
        return Result(
            target,
            adapter.javaClass.name + "." + sqlMethodName +
                ":binder-cast:" +
                (if (statementType.isInterface) "proxy" else "null-statement") +
                ":" + ownership +
                (if (binder.isSynthetic || binder.isBridge) ":synthetic-bridge" else "")
        )
    }

    /**
     * Narrow exception to the global reflection policy: generated Room
     * adapters rely on synthetic/bridge methods for erased generic binds.
     * Abstract contracts are still excluded and signatures are deduplicated.
     */
    private fun generatedAdapterMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { !Modifier.isAbstract(it.modifiers) }
            .distinctBy { method ->
                method.declaringClass.name + "|" + method.name + "|" +
                    method.parameterTypes.joinToString(",") { it.name } + "|" +
                    method.returnType.name
            }
            .toList()

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
            Regex("""cannot be cast to ([A-Za-z0-9_.$]+)"""),
            Regex("""cannot be cast to type ([A-Za-z0-9_.$]+)""")
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
