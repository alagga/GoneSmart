package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Read-only structural fallback for generated Room queue entities.
 *
 * Some GMMP 4.2.1 DAO query carriers no longer expose their writer entities as
 * a materialized List. Reconstruct an entity only when either GMMP's generated
 * Room binding adapter proves the constructor argument mapping or, for a
 * concrete R8 statement contract that cannot be proxied, one unique numeric
 * constructor/field shape reproduces the already-verified Cursor identities.
 *
 * No SQL is executed here. The caller still correlates every reconstructed row
 * against the read-only Cursor before any native DAO writer is eligible.
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

        // Preferred path: a proxyable generated Room INSERT adapter proves
        // the exact bind-column order without executing a statement.
        findBindingAdapter(dao, modelClass)?.let { adapter ->
            val probeItem = chooseProbeItem(context.items) ?: return@let
            val mapping = resolveConstructorMapping(
                modelClass = modelClass,
                adapter = adapter,
                probe = probeItem
            ) ?: return@let
            val rows = context.items.map { item ->
                instantiate(mapping, item) ?: return@let
            }
            if (!rows.all(modelClass::isInstance)) return@let
            if (!rows.zip(context.items).all { (row, item) ->
                    boundColumns(adapter, row)?.let {
                        matchesExpected(it, item)
                    } == true
                }
            ) return@let
            return Result(
                rows = rows,
                boundary = "generated-binding:" +
                    adapter.owner.javaClass.name + "." +
                    adapter.sqlMethod.name
            )
        }

        // GMMP 4.2.1 r25 exposes the generated adapter binder as
        // G(yb4,Object), where yb4 is a concrete host statement class. We
        // deliberately do not instantiate that SQLite statement. Once entity
        // ownership is independently proven (adapter bridge or unique embedded
        // queue wrapper), recover only a constructor whose resulting numeric
        // fields reproduce all four live Cursor columns exactly.
        return reconstructFromNumericShape(modelClass, context)
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
            val methods = generatedAdapterMethods(owner.javaClass)
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
                    ")" +
                    (if (it.isSynthetic || it.isBridge) "[bridge]" else "")
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
            val methods = generatedAdapterMethods(owner.javaClass)
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
                // Capturing bind values requires a no-op proxy. Concrete yb4
                // adapters are handled by the numeric-shape path instead.
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

    private fun reconstructFromNumericShape(
        modelClass: Class<*>,
        context: QueueContext
    ): Result? {
        val constructors = modelClass.declaredConstructors.filter { ctor ->
            ctor.parameterCount == 4 &&
                ctor.parameterTypes.all(::isNumericType)
        }
        if (constructors.isEmpty()) return null

        // A handful of spread-out rows is enough to reject wrong permutations
        // before allocating the full (possibly thousands-row) native model set.
        val witnesses = witnessItems(context.items)
        val candidates = arrayListOf<Pair<ConstructorMapping, String>>()
        constructors.forEach { ctor ->
            ctor.isAccessible = true
            val probeSpecs = specs(witnesses.first())
            permutationsFor(ctor.parameterTypes, probeSpecs)
                .forEach { ordered ->
                    val mapping = ConstructorMapping(
                        ctor,
                        ordered.map { it.name }
                    )
                    val rows = witnesses.map { item ->
                        instantiate(mapping, item) ?: return@forEach
                    }
                    val fingerprint = verifiedNumericFingerprint(
                        rows,
                        witnesses
                    ) ?: return@forEach
                    candidates += mapping to fingerprint
                }
        }
        if (candidates.isEmpty()) return null

        // Different constructor permutations can be semantically equivalent
        // when queue_position == shuffle_position in a tiny queue. Collapse
        // only those that produce exactly the same native numeric field values.
        val byFingerprint = candidates.groupBy { it.second }
        if (byFingerprint.size != 1) return null
        val mapping = byFingerprint.values.single().first().first
        val rows = context.items.map { item ->
            instantiate(mapping, item) ?: return null
        }
        if (verifiedNumericFingerprint(rows, context.items) == null) return null
        return Result(
            rows = rows,
            boundary = "numeric-shape:" + modelClass.name
        )
    }

    private fun witnessItems(items: List<QueueItemInfo>): List<QueueItemInfo> {
        if (items.size <= 12) return items
        val indexes = linkedSetOf(
            0,
            1,
            items.size / 4,
            items.size / 2,
            (items.size * 3) / 4,
            items.size - 2,
            items.size - 1
        ).filter { it in items.indices }
        return indexes.map(items::get)
    }

    /**
     * Returns a fingerprint only when four distinct numeric fields can explain
     * queue_id, song_id, queue_position and shuffle_position for every row.
     */
    private fun verifiedNumericFingerprint(
        rows: List<Any>,
        items: List<QueueItemInfo>
    ): String? {
        if (rows.size != items.size || rows.isEmpty()) return null
        val model = rows.first().javaClass
        if (!rows.all(model::isInstance)) return null
        val fields = hierarchyFields(model).filter {
            isNumericType(it.type)
        }.onEach { it.isAccessible = true }
        if (fields.size < 4) return null

        fun matches(field: java.lang.reflect.Field, expected: (QueueItemInfo) -> Long): Boolean =
            rows.indices.all { index ->
                val actual = runCatching {
                    (field.get(rows[index]) as? Number)?.toLong()
                }.getOrNull() ?: return@all false
                actual == expected(items[index])
            }

        val queueIds = fields.filter { matches(it) { item -> item.queueEntryId } }
        val trackIds = fields.filter { matches(it) { item -> item.track.id } }
        val positions = fields.filter {
            matches(it) { item -> item.queuePosition.toLong() }
        }
        val shuffles = fields.filter {
            matches(it) { item -> item.shufflePosition.toLong() }
        }
        val tuples = arrayListOf<List<java.lang.reflect.Field>>()
        queueIds.forEach { queueId ->
            trackIds.forEach { trackId ->
                positions.forEach { position ->
                    shuffles.forEach { shuffle ->
                        val tuple = listOf(queueId, trackId, position, shuffle)
                        if (tuple.distinct().size == 4) tuples += tuple
                    }
                }
            }
        }
        if (tuples.isEmpty()) return null

        val numeric = fields.sortedBy {
            it.declaringClass.name + "|" + it.name
        }
        return buildString {
            append(model.name)
            numeric.forEach { field ->
                append('|').append(field.name).append('=')
                rows.forEach { row ->
                    append(
                        runCatching {
                            (field.get(row) as? Number)?.toLong()
                        }.getOrNull() ?: Long.MIN_VALUE
                    ).append(',')
                }
            }
        }
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
