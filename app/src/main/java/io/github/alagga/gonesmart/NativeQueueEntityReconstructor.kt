package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Read-only structural fallback for generated Room queue entities.
 *
 * Reconstruction is allowed only when GMMP's own generated Room adapter proves
 * queue_table ownership through its SQL text and bind callback. The adapter is
 * invoked only against a fake binder; no database statement is created or
 * executed. The caller must still correlate every reconstructed row against
 * the live read-only Cursor before any native DAO writer becomes eligible.
 *
 * GMMP 4.2.1 device evidence disproved the older Predicate[] -> List fallback:
 * the observed inherited method queried the tracks table, not queue_table.
 * Predicate contracts therefore have no role in entity reconstruction.
 */
internal object NativeQueueEntityReconstructor {
    data class Result(
        val rows: List<Any>,
        val boundary: String
    )

    private data class AdapterBinding(
        val owner: Any,
        val sqlMethod: Method,
        val bindMethod: Method,
        val columns: List<String>
    )

    private data class ValueSpec(
        val name: String,
        val value: Long
    )

    private data class ConstructorMapping(
        val constructor: Constructor<*>,
        val argumentNames: List<String>
    )

    fun reconstruct(
        dao: Any,
        modelClass: Class<*>,
        context: QueueContext
    ): Result? {
        if (context.items.isEmpty()) return null
        val adapter = findBindingAdapter(dao, modelClass) ?: return null
        val probeItem = chooseProbeItem(context.items) ?: return null
        val mapping = resolveConstructorMapping(
            modelClass = modelClass,
            adapter = adapter,
            probe = probeItem
        ) ?: return null

        val rows = context.items.map { item ->
            instantiate(mapping, item) ?: return null
        }
        if (!rows.all(modelClass::isInstance)) return null

        if (!rows.zip(context.items).all { (row, item) ->
                boundColumns(adapter, row)?.let {
                    matchesExpected(it, item)
                } == true
            }
        ) return null

        return Result(
            rows = rows,
            boundary = "generated-binding:" +
                adapter.owner.javaClass.name + "." +
                adapter.sqlMethod.name
        )
    }

    fun diagnosticShape(
        dao: Any,
        modelClass: Class<*>
    ): String {
        val constructors = modelClass.declaredConstructors.joinToString(",") {
            it.parameterTypes.joinToString(
                prefix = modelClass.name + "(",
                postfix = ")"
            ) { type -> type.name }
        }.ifBlank { "none" }
        val adapters = hierarchyFields(dao.javaClass).mapNotNull { field ->
            field.isAccessible = true
            val owner = runCatching { field.get(dao) }.getOrNull()
                ?: return@mapNotNull null
            val methods = GmmpReflectionPolicy.callableMethods(owner.javaClass)
                .filter { !Modifier.isStatic(it.modifiers) }
            val sql = methods.filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }.mapNotNull { method ->
                runCatching {
                    method.isAccessible = true
                    val value = method.invoke(owner) as? String
                        ?: return@runCatching null
                    if (!value.contains("queue_table", ignoreCase = true)) {
                        return@runCatching null
                    }
                    method.name + ":" +
                        value.trimStart()
                            .substringBefore(' ')
                            .uppercase() +
                        ":" +
                        (bindColumns(value)?.joinToString("+")
                            ?: "unparsed")
                }.getOrNull()
            }
            if (sql.isEmpty()) return@mapNotNull null
            val bind = methods.filter {
                it.parameterCount == 2 &&
                    it.returnType == java.lang.Void.TYPE
            }.joinToString(",") {
                it.name + "(" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    ")"
            }.ifBlank { "none" }
            field.name + "->" + owner.javaClass.name +
                "{sql=" + sql.joinToString(",") +
                ";bind=" + bind + "}"
        }.joinToString(";").ifBlank { "none" }
        return "model=" + modelClass.name +
            " | ctors=" + constructors +
            " | adapters=" + adapters
    }

    private fun chooseProbeItem(items: List<QueueItemInfo>): QueueItemInfo? =
        items.firstOrNull {
            it.queuePosition.toLong() != it.shufflePosition.toLong() &&
                it.queueEntryId != it.track.id
        } ?: items.firstOrNull {
            it.queueEntryId != it.track.id
        }

    private fun findBindingAdapter(
        dao: Any,
        modelClass: Class<*>
    ): AdapterBinding? {
        val candidates = hierarchyFields(dao.javaClass).mapNotNull { field ->
            field.isAccessible = true
            val owner = runCatching { field.get(dao) }.getOrNull()
                ?: return@mapNotNull null
            val methods = GmmpReflectionPolicy.callableMethods(owner.javaClass)
                .filter { !Modifier.isStatic(it.modifiers) }
            val sqlMethods = methods.filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }
            val bindMethods = methods.filter {
                it.parameterCount == 2 &&
                    it.returnType == java.lang.Void.TYPE &&
                    (
                        it.parameterTypes[1] == Any::class.java ||
                            it.parameterTypes[1].isAssignableFrom(modelClass)
                    )
            }
            for (sqlMethod in sqlMethods) {
                val sql = runCatching {
                    sqlMethod.isAccessible = true
                    sqlMethod.invoke(owner) as? String
                }.getOrNull() ?: continue
                val columns = bindColumns(sql) ?: continue
                val bind = bindMethods.singleOrNull {
                    it.parameterTypes[0].isInterface
                } ?: continue
                bind.isAccessible = true
                return@mapNotNull AdapterBinding(
                    owner = owner,
                    sqlMethod = sqlMethod,
                    bindMethod = bind,
                    columns = columns
                )
            }
            null
        }
        return candidates
            .distinctBy {
                it.owner.javaClass.name + "|" +
                    it.sqlMethod.name + "|" +
                    it.columns.joinToString(",")
            }
            .singleOrNull()
    }

    private fun resolveConstructorMapping(
        modelClass: Class<*>,
        adapter: AdapterBinding,
        probe: QueueItemInfo
    ): ConstructorMapping? {
        val specs = specs(probe)
        val candidates = arrayListOf<ConstructorMapping>()
        modelClass.declaredConstructors
            .filter { ctor ->
                ctor.parameterCount == specs.size &&
                    ctor.parameterTypes.all(::isNumericType)
            }
            .forEach { ctor ->
                ctor.isAccessible = true
                permutationsFor(ctor.parameterTypes, specs)
                    .forEach permutation@{ orderedSpecs ->
                        val args = orderedSpecs.mapIndexed { index, spec ->
                            coerce(spec.value, ctor.parameterTypes[index])
                        }.toTypedArray()
                        val instance = runCatching {
                            ctor.newInstance(*args)
                        }.getOrNull() ?: return@permutation
                        val bound = boundColumns(adapter, instance)
                            ?: return@permutation
                        if (matchesExpected(bound, probe)) {
                            candidates += ConstructorMapping(
                                constructor = ctor,
                                argumentNames = orderedSpecs.map { it.name }
                            )
                        }
                    }
            }
        return candidates.distinctBy {
            it.constructor.toGenericString() + "|" +
                it.argumentNames.joinToString(",")
        }.singleOrNull()
    }

    private fun instantiate(
        mapping: ConstructorMapping,
        item: QueueItemInfo
    ): Any? {
        val values = specs(item).associate { it.name to it.value }
        val types = mapping.constructor.parameterTypes
        val args = mapping.argumentNames.mapIndexed { index, name ->
            coerce(values.getValue(name), types[index])
        }.toTypedArray()
        return runCatching {
            mapping.constructor.newInstance(*args)
        }.getOrNull()
    }

    private fun boundColumns(
        adapter: AdapterBinding,
        entity: Any
    ): Map<String, Long>? {
        val binderType = adapter.bindMethod.parameterTypes[0]
        if (!binderType.isInterface) return null
        val values = linkedMapOf<Int, Long?>()
        val proxy = Proxy.newProxyInstance(
            binderType.classLoader ?: entity.javaClass.classLoader,
            arrayOf(binderType)
        ) { proxyObject, method, args ->
            when (method.name) {
                "toString" -> "GoneSmart queue bind probe"
                "hashCode" -> System.identityHashCode(proxyObject)
                "equals" -> proxyObject === args?.firstOrNull()
                else -> {
                    val index =
                        (args?.firstOrNull() as? Number)?.toInt()
                    if (index != null) {
                        if ((args?.size ?: 0) >= 2) {
                            val value = args?.get(1)
                            if (value is Number) {
                                values[index] = value.toLong()
                            }
                        } else if (
                            method.returnType == java.lang.Void.TYPE
                        ) {
                            values.putIfAbsent(index, null)
                        }
                    }
                    primitiveDefault(method.returnType)
                }
            }
        }
        runCatching {
            adapter.bindMethod.invoke(adapter.owner, proxy, entity)
        }.getOrElse { return null }

        val result = linkedMapOf<String, Long>()
        adapter.columns.forEachIndexed { zeroIndex, column ->
            val value = values[zeroIndex + 1] ?: return null
            result[column] = value
        }
        return result
    }

    private fun matchesExpected(
        bound: Map<String, Long>,
        item: QueueItemInfo
    ): Boolean =
        bound["queue_id"] == item.queueEntryId &&
            bound["queue_track_id"] == item.track.id &&
            bound["queue_position"] == item.queuePosition.toLong() &&
            bound["queue_shuffle_position"] ==
                item.shufflePosition.toLong()

    internal fun bindColumns(sql: String): List<String>? {
        if (!sql.contains("queue_table", ignoreCase = true) ||
            !sql.trimStart().startsWith("INSERT", ignoreCase = true)
        ) return null
        val normalized = sql.replace('`', ' ')
        val tableIndex = normalized.indexOf(
            "queue_table",
            ignoreCase = true
        )
        if (tableIndex < 0) return null
        val open = normalized.indexOf('(', tableIndex)
        val close = if (open >= 0) normalized.indexOf(')', open + 1) else -1
        if (open < 0 || close <= open) return null
        val columns = normalized.substring(open + 1, close)
            .split(',')
            .map { it.trim().lowercase() }
        val expected = setOf(
            "queue_id",
            "queue_track_id",
            "queue_position",
            "queue_shuffle_position"
        )
        if (columns.toSet() != expected || columns.size != expected.size) {
            return null
        }
        val valuesPart = normalized.substring(close + 1)
        if (valuesPart.count { it == '?' } != columns.size) return null
        return columns
    }

    private fun specs(item: QueueItemInfo): List<ValueSpec> = listOf(
        ValueSpec("queue_id", item.queueEntryId),
        ValueSpec("queue_track_id", item.track.id),
        ValueSpec("queue_position", item.queuePosition.toLong()),
        ValueSpec(
            "queue_shuffle_position",
            item.shufflePosition.toLong()
        )
    )

    private fun permutationsFor(
        types: Array<Class<*>>,
        specs: List<ValueSpec>
    ): List<List<ValueSpec>> {
        val result = arrayListOf<List<ValueSpec>>()
        fun visit(
            index: Int,
            remaining: List<ValueSpec>,
            current: List<ValueSpec>
        ) {
            if (index == types.size) {
                result += current
                return
            }
            remaining.forEachIndexed { remainingIndex, spec ->
                if (!fits(spec.value, types[index])) return@forEachIndexed
                visit(
                    index + 1,
                    remaining.filterIndexed { i, _ -> i != remainingIndex },
                    current + spec
                )
            }
        }
        visit(0, specs, emptyList())
        return result
    }

    private fun fits(value: Long, type: Class<*>): Boolean = when (type) {
        Integer.TYPE, Integer::class.java ->
            value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        java.lang.Long.TYPE, java.lang.Long::class.java -> true
        java.lang.Short.TYPE, java.lang.Short::class.java ->
            value in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
        else -> false
    }

    private fun coerce(value: Long, type: Class<*>): Any = when (type) {
        Integer.TYPE, Integer::class.java -> value.toInt()
        java.lang.Long.TYPE, java.lang.Long::class.java -> value
        java.lang.Short.TYPE, java.lang.Short::class.java -> value.toShort()
        else -> error("Unsupported queue numeric type " + type.name)
    }

    private fun isNumericType(type: Class<*>): Boolean =
        type == Integer.TYPE || type == Integer::class.java ||
            type == java.lang.Long.TYPE || type == java.lang.Long::class.java ||
            type == java.lang.Short.TYPE || type == java.lang.Short::class.java

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
}
