package io.github.alagga.gonesmart

import android.database.Cursor
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Read-only SQL bridge through GMMP's already-open Room database.
 *
 * This intentionally resolves by semantic structure instead of R8 names:
 * - the unique direct Auto-DJ field whose runtime hierarchy is GMDatabase /
 *   RoomDatabase;
 * - the unique concrete database method taking one query object and
 *   returning Cursor;
 * - that query object's unique (String, array) constructor.
 *
 * It never opens GMMP's database file itself and never executes mutations.
 */
internal object GmmpReadOnlySql {
    private const val TAG = "GoneSmart"
    private val reportedBindings =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class Binding(
        val database: Any,
        val databaseField: Field,
        val queryMethod: Method,
        val queryConstructor: Constructor<*>
    )

    fun <T> query(
        autoDjInstance: Any,
        sql: String,
        args: Array<Any?> = emptyArray(),
        reader: (Cursor) -> T
    ): T {
        val binding = resolve(autoDjInstance)
        val query = newQuery(binding.queryConstructor, sql, args)
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
                binding.queryMethod.name + "|" +
                binding.queryConstructor.declaringClass.name

        if (reportedBindings.add(key)) {
            Log.i(
                TAG,
                "GMMP READ-ONLY SQL MAPPING | databaseField=" +
                    binding.databaseField.name +
                    " | database=" + binding.database.javaClass.name +
                    " | query=" + binding.queryMethod.declaringClass.name +
                    "." + binding.queryMethod.name +
                    "(" + binding.queryMethod.parameterTypes.single().name +
                    "):Cursor" +
                    " | wrapper=" +
                    binding.queryConstructor.declaringClass.name
            )
        }

        return cursor.use(reader)
    }

    fun canResolve(autoDjInstance: Any): Boolean =
        runCatching { resolve(autoDjInstance) }.isSuccess

    private fun resolve(autoDjInstance: Any): Binding {
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
            val constructor: Constructor<*>
        )

        val cursorCandidates = hierarchyMethods(database.javaClass)
            .filter {
                it.parameterCount == 1 &&
                    Cursor::class.java.isAssignableFrom(it.returnType)
            }
            .mapNotNull { method ->
                resolveQueryConstructor(method.parameterTypes.single())
                    ?.let { Candidate(method, it) }
            }

        val chosen = cursorCandidates.singleOrNull() ?: error(
            "GMMP Cursor query boundary is not structurally unique: " +
                cursorCandidates.joinToString(",") {
                    it.method.declaringClass.name + "." +
                        it.method.name + "(" +
                        it.method.parameterTypes.single().name + ")"
                }.ifBlank { "none" }
        )

        databaseField.isAccessible = true
        chosen.method.isAccessible = true
        chosen.constructor.isAccessible = true

        return Binding(
            database = database,
            databaseField = databaseField,
            queryMethod = chosen.method,
            queryConstructor = chosen.constructor
        )
    }

    private fun resolveQueryConstructor(
        queryType: Class<*>
    ): Constructor<*>? {
        val candidates = queryType.declaredConstructors.filter {
            val p = it.parameterTypes
            p.size == 2 &&
                p[0] == String::class.java &&
                p[1].isArray &&
                !p[1].componentType.isPrimitive
        }
        return candidates.singleOrNull()
    }

    private fun newQuery(
        constructor: Constructor<*>,
        sql: String,
        args: Array<Any?>
    ): Any {
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
