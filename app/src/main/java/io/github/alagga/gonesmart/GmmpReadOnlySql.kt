package io.github.alagga.gonesmart

import android.database.Cursor
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Read-only SQL bridge through GMMP's already-open Room database.
 *
 * This intentionally resolves by semantic structure instead of R8 names.
 * GMMP 4.2.0 used a concrete (String,Object[]) query wrapper. GMMP 4.2.1
 * exposes an R8-renamed Room pooled query object (p94) whose visible
 * constructor is only (int). On the tested 4.2.1 build the acquire/init
 * helpers are inlined/renamed away; for parameter-free read-only queries we
 * initialize only the uniquely verified mutable SQL field and leave Room's
 * default argument count at zero. Some builds may expose the
 * SupportSQLiteQuery contract as an interface. All paths stay read-only.
 */
internal object GmmpReadOnlySql {
    private const val TAG = "GoneSmart"
    private val reportedBindings =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Successful structural discovery is process-stable for one live GMMP
    // Auto-DJ/service object. Re-running the full hierarchy scan for every
    // queue poll was visible as UI jank in the 4.2.1 device log.
    private val bindingCache = java.util.WeakHashMap<Any, Binding>()

    private data class Binding(
        val database: Any,
        val databaseField: Field,
        val queryMethod: Method,
        val queryType: Class<*>,
        val queryConstructor: Constructor<*>?,
        val queryFactory: Method?,
        val capacityConstructor: Constructor<*>?,
        val directInitializer: Method?
    )

    fun <T> query(
        autoDjInstance: Any,
        sql: String,
        args: Array<Any?> = emptyArray(),
        reader: (Cursor) -> T
    ): T {
        val binding = resolve(autoDjInstance)
        val query = newQuery(binding, sql, args)
        val cursor = binding.queryMethod.invoke(
            binding.database,
            query
        ) as? Cursor ?: error(
            "Resolved GMMP read-only query did not return Cursor"
        )

        val key =
            binding.database.javaClass.name + "|" +
                binding.databaseField.name + "|" +
                binding.queryMethod.declaringClass.name + "." +
                binding.queryMethod.name + "|" + binding.queryType.name

        if (reportedBindings.add(key)) {
            Log.i(
                TAG,
                "GMMP READ-ONLY SQL MAPPING | databaseField=" +
                    binding.databaseField.name +
                    " | database=" + binding.database.javaClass.name +
                    " | query=" + binding.queryMethod.declaringClass.name +
                    "." + binding.queryMethod.name +
                    "(" + binding.queryType.name + "):Cursor" +
                    " | wrapper=" + binding.queryType.name +
                    " | factory=" +
                    when {
                        binding.queryConstructor != null -> "constructor"
                        binding.queryFactory != null -> "static-query-factory"
                        binding.capacityConstructor != null ->
                            if (binding.directInitializer != null) {
                                "direct-capacity+initializer"
                            } else {
                                "direct-capacity+fields"
                            }
                        else -> "interface-proxy"
                    }
            )
        }

        return cursor.use(reader)
    }

    private class DirectDatabaseHost(
        val database: Any
    )

    fun <T> queryDatabase(
        databaseInstance: Any,
        sql: String,
        args: Array<Any?> = emptyArray(),
        reader: (Cursor) -> T
    ): T = query(
        autoDjInstance = DirectDatabaseHost(databaseInstance),
        sql = sql,
        args = args,
        reader = reader
    )

    fun canResolve(autoDjInstance: Any): Boolean =
        runCatching { resolve(autoDjInstance) }.isSuccess

    private fun resolve(autoDjInstance: Any): Binding {
        synchronized(bindingCache) {
            bindingCache[autoDjInstance]
        }?.let { return it }

        val databases = hierarchyFields(autoDjInstance.javaClass)
            .mapNotNull { field ->
                field.isAccessible = true
                val value = runCatching {
                    field.get(autoDjInstance)
                }.getOrNull() ?: return@mapNotNull null
                if (isDatabaseLike(field.type, value.javaClass)) {
                    field to value
                } else {
                    null
                }
            }
            .distinctBy { it.second.javaClass.name + "|" + it.first.name }

        val (databaseField, database) =
            databases.singleOrNull() ?: error(
                "GMMP database instance is not structurally unique: " +
                    databases.joinToString(",") {
                        it.first.name + "->" + it.second.javaClass.name
                    }.ifBlank { "none" }
            )

        data class Candidate(
            val method: Method,
            val queryType: Class<*>,
            val constructor: Constructor<*>?,
            val factory: Method?,
            val capacityConstructor: Constructor<*>?,
            val directInitializer: Method?
        )

        // Android framework classes normally share the boot class loader,
        // but Xposed/host reflection can still make Class identity checks too
        // strict. The r13 device inventory proves the real boundary as
        // f94.q(p94):android.database.Cursor, so accept the exact framework
        // type name as the compatibility-safe fallback.
        val cursorCandidates =
            GmmpReflectionPolicy.callableMethods(database.javaClass)
                .filter {
                    it.parameterCount == 1 &&
                        isCursorType(it.returnType)
                }
                .map { method ->
                    val queryType = method.parameterTypes.single()
                    Candidate(
                        method,
                        queryType,
                        GmmpReadOnlyQueryShape.legacyConstructor(queryType),
                        GmmpReadOnlyQueryShape.pooledFactory(queryType),
                        GmmpReadOnlyQueryShape.capacityConstructor(queryType),
                        GmmpReadOnlyQueryShape.directInitializer(queryType)
                    )
                }

        val chosen = cursorCandidates.singleOrNull() ?: error(
            "GMMP Cursor query boundary is not structurally unique: " +
                cursorCandidates.joinToString(",") {
                    it.method.declaringClass.name + "." +
                        it.method.name + "(" + it.queryType.name + ")"
                }.ifBlank { "none" }
        )

        val directCarrier =
            chosen.capacityConstructor != null &&
                (
                    chosen.directInitializer != null ||
                        GmmpReadOnlyQueryShape
                            .directFieldLayout(chosen.queryType) != null
                )

        if (chosen.constructor == null &&
            chosen.factory == null &&
            !directCarrier &&
            !chosen.queryType.isInterface
        ) {
            error(
                "GMMP Cursor query type has no safe factory: " +
                    chosen.queryType.name +
                    " | constructors=" +
                    chosen.queryType.declaredConstructors.joinToString(",") {
                        it.parameterTypes.joinToString(
                            prefix = "(", postfix = ")"
                        ) { p -> p.name }
                    } +
                    " | staticMethods=" +
                    chosen.queryType.declaredMethods
                        .filter { Modifier.isStatic(it.modifiers) }
                        .take(24)
                        .joinToString(",") { method ->
                            method.name +
                                method.parameterTypes.joinToString(
                                    prefix = "(", postfix = ")"
                                ) { it.name } +
                                ":" + method.returnType.name
                        }
                        .ifBlank { "none" }
            )
        }

        databaseField.isAccessible = true
        chosen.method.isAccessible = true
        chosen.constructor?.isAccessible = true
        chosen.factory?.isAccessible = true
        chosen.capacityConstructor?.isAccessible = true
        chosen.directInitializer?.isAccessible = true

        val resolved = Binding(
            database = database,
            databaseField = databaseField,
            queryMethod = chosen.method,
            queryType = chosen.queryType,
            queryConstructor = chosen.constructor,
            queryFactory = chosen.factory,
            capacityConstructor = chosen.capacityConstructor,
            directInitializer = chosen.directInitializer
        )
        synchronized(bindingCache) {
            bindingCache[autoDjInstance] = resolved
        }
        return resolved
    }

    private fun isCursorType(type: Class<*>): Boolean =
        Cursor::class.java.isAssignableFrom(type) ||
            type.name == "android.database.Cursor"

    private fun supportsQueryInterface(queryType: Class<*>): Boolean {
        // The Cursor-returning Room boundary is already unique on the
        // concrete GMDatabase implementation. R8 may add bridge/default
        // methods to SupportSQLiteQuery, so exact method COUNTS are not a
        // stable contract. Accept the interface when it exposes the two
        // semantic operations Room needs; argument-count is optional.
        if (!queryType.isInterface) return false
        val methods = queryType.methods.filter { !it.isSynthetic }
        val hasSql = methods.any {
            it.parameterCount == 0 &&
                it.returnType == String::class.java
        }
        val hasBinder = methods.any {
            it.parameterCount == 1 &&
                it.returnType == java.lang.Void.TYPE &&
                !it.parameterTypes[0].isPrimitive
        }
        return hasSql && hasBinder
    }

    private fun newQuery(
        binding: Binding,
        sql: String,
        args: Array<Any?>
    ): Any {
        val constructor = binding.queryConstructor
        if (constructor != null) {
            val arrayType = constructor.parameterTypes[1]
            val component = arrayType.componentType
            val nativeArgs = java.lang.reflect.Array.newInstance(
                component,
                args.size
            )
            args.forEachIndexed { index, value ->
                if (value != null && !component.isInstance(value) &&
                    component != Any::class.java
                ) {
                    error(
                        "GMMP query argument " + index +
                            " does not match " + component.name
                    )
                }
                java.lang.reflect.Array.set(nativeArgs, index, value)
            }
            return constructor.newInstance(sql, nativeArgs)
        }

        binding.queryFactory?.let { factory ->
            val query = factory.invoke(null, sql, args.size)
                ?: error("GMMP pooled query factory returned null")
            require(binding.queryType.isInstance(query)) {
                "GMMP pooled query factory returned unexpected type"
            }
            bindArguments(query, args)
            return query
        }

        binding.capacityConstructor?.let {
            val query = GmmpReadOnlyQueryShape.newDirectCarrier(
                type = binding.queryType,
                sql = sql,
                argumentCount = args.size
            ) ?: error("GMMP direct query carrier initialization failed")
            require(binding.queryType.isInstance(query)) {
                "GMMP direct query constructor returned unexpected type"
            }
            bindArguments(query, args)
            return query
        }

        val queryType = binding.queryType
        require(queryType.isInterface) {
            "GMMP query type is neither constructible, pooled nor interface"
        }
        val loader = queryType.classLoader
            ?: binding.database.javaClass.classLoader
        return Proxy.newProxyInstance(
            loader,
            arrayOf(queryType)
        ) { proxy, method, methodArgs ->
            when {
                method.declaringClass == Any::class.java &&
                    method.name == "toString" ->
                    "GoneSmart read-only query"
                method.declaringClass == Any::class.java &&
                    method.name == "hashCode" ->
                    System.identityHashCode(proxy)
                method.declaringClass == Any::class.java &&
                    method.name == "equals" ->
                    proxy === methodArgs?.firstOrNull()
                method.parameterCount == 0 &&
                    method.returnType == String::class.java ->
                    sql
                method.parameterCount == 0 &&
                    (method.returnType == Integer.TYPE ||
                        method.returnType == Integer::class.java) ->
                    args.size
                method.parameterCount == 1 &&
                    method.returnType == java.lang.Void.TYPE -> {
                    bindArguments(methodArgs?.firstOrNull(), args)
                    null
                }
                method.isDefault -> null
                else -> error(
                    "Unsupported GMMP query-interface method: " +
                        method.toGenericString()
                )
            }
        }
    }

    private fun bindArguments(
        program: Any?,
        args: Array<Any?>
    ) {
        if (args.isEmpty()) return
        val target = program ?: error("GMMP query binder is null")
        val methods = hierarchyMethods(target.javaClass)

        fun unique(vararg types: Class<*>): Method {
            return methods.filter { method ->
                method.returnType == java.lang.Void.TYPE &&
                    method.parameterTypes.contentEquals(types)
            }.singleOrNull()?.apply {
                isAccessible = true
            } ?: error(
                "GMMP bind method is not structurally unique for " +
                    types.joinToString(",") { it.name }
            )
        }

        val bindNull by lazy { unique(Integer.TYPE) }
        val bindLong by lazy {
            unique(Integer.TYPE, java.lang.Long.TYPE)
        }
        val bindDouble by lazy {
            unique(Integer.TYPE, java.lang.Double.TYPE)
        }
        val bindString by lazy {
            unique(Integer.TYPE, String::class.java)
        }
        val bindBlob by lazy {
            unique(Integer.TYPE, ByteArray::class.java)
        }

        args.forEachIndexed { index, value ->
            val slot = index + 1
            when (value) {
                null -> bindNull.invoke(target, slot)
                is ByteArray -> bindBlob.invoke(target, slot, value)
                is Float -> bindDouble.invoke(target, slot, value.toDouble())
                is Double -> bindDouble.invoke(target, slot, value)
                is Boolean -> bindLong.invoke(
                    target, slot, if (value) 1L else 0L
                )
                is Byte, is Short, is Int, is Long ->
                    bindLong.invoke(target, slot, (value as Number).toLong())
                is String -> bindString.invoke(target, slot, value)
                else -> bindString.invoke(target, slot, value.toString())
            }
        }
    }

    private fun isDatabaseLike(
        declared: Class<*>,
        runtime: Class<*>
    ): Boolean {
        if (declared.name ==
            "gonemad.gmmp.data.database.GMDatabase"
        ) return true
        return generateSequence<Class<*>>(runtime) { it.superclass }
            .any {
                it.name == "androidx.room.RoomDatabase" ||
                    it.name ==
                        "gonemad.gmmp.data.database.GMDatabase"
            }
    }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .toList()

    private fun hierarchyMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                !Modifier.isAbstract(it.modifiers) &&
                    !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    "|" + it.returnType.name
            }
            .toList()
}
