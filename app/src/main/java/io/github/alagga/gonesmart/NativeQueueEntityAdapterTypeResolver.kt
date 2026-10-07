package io.github.alagga.gonesmart

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type

/**
 * Resolves a Room queue entity only from a generated DAO adapter whose own
 * SQL proves that it writes queue_table.
 *
 * A generated DAO may own several insertion/update/delete adapters for models
 * that are related to the DAO but are not the queue_table writer entity. Mere
 * field ownership or a generic DML string is therefore not enough. The
 * adapter's no-arg SQL boundary must name queue_table before its erased
 * bind(statement,Object) bridge or preserved generic metadata can be used as
 * entity-type evidence.
 *
 * This resolver is read-only. It calls only generated-adapter SQL-description
 * methods after the erased binder shape has already proved that the field is
 * adapter-like. The binder callback itself is then invoked only with a fake
 * statement / deliberately wrong marker object. No SQLite statement is
 * created or executed.
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
                field.declaringClass.name + "." + field.name to result
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
        // Room's generic EntityInsertion/Deletion/Update adapters expose the
        // erased bind(statement,Object) boundary as a compiler bridge. On the
        // tested GMMP 4.2.1 build that bridge is synthetic. The general
        // GmmpReflectionPolicy deliberately excludes synthetic methods, which
        // is correct for broad host discovery but wrong at this already-owned
        // generated Room adapter boundary. Include bridges locally here only.
        val methods = generatedAdapterMethods(adapter.javaClass)
            .filter { !Modifier.isStatic(it.modifiers) }

        // Prove the object is adapter-like before invoking any no-arg String
        // method on it. This prevents arbitrary DAO/database helper objects
        // from becoming executable diagnostics merely because R8 gave them a
        // String-returning method.
        val allBinders = methods.filter {
            it.declaringClass != Any::class.java &&
                it.parameterCount == 2 &&
                !it.parameterTypes[0].isPrimitive &&
                it.returnType == java.lang.Void.TYPE &&
                it.parameterTypes[1] == Any::class.java
        }
        val directBinders = allBinders.filter {
            it.declaringClass == adapter.javaClass
        }
        val binder = directBinders.ifEmpty { allBinders }
            .singleOrNull() ?: return null

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
        val queueSqlGroups = sqlValues
            .filter { (_, value) ->
                value.contains("queue_table", ignoreCase = true)
            }
            .groupBy { (_, value) -> normalizeSql(value) }

        // Java reflection may expose both an adapter override and the same
        // inherited virtual SQL boundary. Invoking either on the runtime
        // adapter yields the same SQL, which is one semantic proof rather
        // than two ambiguous candidates. Different queue_table SQL strings
        // on the same adapter remain ambiguous and fail closed.
        if (queueSqlGroups.size != 1) return null
        val queueSql = queueSqlGroups.values.single()
            .sortedByDescending { (method, _) ->
                method.declaringClass == adapter.javaClass
            }
            .first()

        val sqlMethodName = queueSql.first.name
        val loader = adapter.javaClass.classLoader
        inferGenericEntity(adapter.javaClass)?.let { type ->
            if (usable(type)) {
                return Result(
                    type,
                    adapter.javaClass.name + "." + sqlMethodName +
                        ":generic:queue-sql"
                )
            }
        }

        // Do not assume SupportSQLiteStatement remains an interface after
        // host/R8 rewriting. The first parameter is opaque here; only a
        // reference type is required because no statement is executed.
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
            // Cast-only discovery path for a concrete statement contract. A
            // real statement must never be constructed just to discover type.
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
                ":queue-sql" +
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
        // Room's generated entity adapter is represented by the adapter's
        // generic superclass contract. Auxiliary interfaces may also retain
        // unrelated generic metadata after R8; merging all type arguments can
        // therefore create false ambiguity. Prefer a unique usable superclass
        // entity and consult interfaces only when the superclass carries none.
        val superclassCandidates = classesFromType(type.genericSuperclass)
            .filter(::usable)
            .distinct()
        when (superclassCandidates.size) {
            1 -> return superclassCandidates.single()
            in 2..Int.MAX_VALUE -> return null
        }

        val interfaceCandidates = type.genericInterfaces
            .flatMap(::classesFromType)
            .filter(::usable)
            .distinct()
        return interfaceCandidates.singleOrNull()
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

    private fun normalizeSql(sql: String): String =
        sql.replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

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
