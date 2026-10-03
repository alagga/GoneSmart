package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Native-first queue entity provider for a DAO whose generated Room adapter
 * has already proved queue_table ownership.
 *
 * Prefer GMMP's own synchronous no-arg List reader when that boundary is
 * unique. The returned native entities are accepted only when the generated
 * INSERT binder reproduces the complete live Cursor identity for every row.
 * This avoids rebuilding obfuscated Room entities when GMMP can supply them
 * directly.
 *
 * Constructor reconstruction remains a bounded fallback for DAO shapes that
 * expose no synchronous List reader. Predicate[] / reactive query fallbacks
 * are intentionally excluded: device evidence showed those structural
 * families can belong to tracks rather than queue_table.
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

    private data class BoundKey(
        val queueId: Long,
        val trackId: Long,
        val position: Long,
        val shufflePosition: Long
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

        // Once queue_table ownership and entity type are proven, a unique
        // synchronous no-arg List-returning method is a much stronger native
        // source than manufacturing entities from an obfuscated constructor.
        // If such a boundary exists but does not correlate with the accepted
        // Cursor snapshot, fail closed instead of silently falling back.
        val nativeReaders = nativeListReaders(dao)
        if (nativeReaders.isNotEmpty()) {
            val reader = nativeReaders.singleOrNull() ?: return null
            return readNativeRows(
                dao = dao,
                reader = reader,
                modelClass = modelClass,
                adapter = adapter,
                context = context
            )
        }

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
        if (!rowsCorrelate(adapter, rows, context)) return null

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
        val readers = nativeListReaders(dao).joinToString(",") {
            it.declaringClass.name + "." + it.name +
                "():" + it.returnType.name
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
                    it.returnType == java.lang.Void.TYPE &&
                    it.parameterTypes[1] == Any::class.java
            }.joinToString(",") {
                it.name + "(" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    ")" +
                    if (it.isSynthetic || it.isBridge) "[bridge]" else ""
            }.ifBlank { "none" }
            field.name + "->" + owner.javaClass.name +
                "{sql=" + sql.joinToString(",") +
                ";bind=" + bind + "}"
        }.joinToString(";").ifBlank { "none" }
        return "model=" + modelClass.name +
            " | ctors=" + constructors +
            " | nativeReaders=" + readers +
            " | adapters=" + adapters
    }

    private fun readNativeRows(
        dao: Any,
        reader: Method,
        modelClass: Class<*>,
        adapter: AdapterBinding,
        context: QueueContext
    ): Result? {
        val raw = runCatching {
            reader.isAccessible = true
            reader.invoke(dao) as? List<*>
        }.getOrNull() ?: return null
        if (raw.size != context.items.size || raw.any { it == null }) return null
        val rows = raw.filterNotNull()
        if (!rows.all(modelClass::isInstance)) return null
        if (!rowsCorrelate(adapter, rows, context)) return null
        return Result(
            rows = rows,
            boundary = "native-list:" +
                reader.declaringClass.name + "." + reader.name
        )
    }

    private fun nativeListReaders(dao: Any): List<Method> =
        GmmpReflectionPolicy.callableMethods(dao.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    java.util.List::class.java.isAssignableFrom(it.returnType)
            }
            .distinctBy {
                it.name + "|" + it.returnType.name
            }

    private fun rowsCorrelate(
        adapter: AdapterBinding,
        rows: List<Any>,
        context: QueueContext
    ): Boolean {
        if (rows.size != context.items.size) return false
        val actual = rows.map { row ->
            val bound = boundColumns(adapter, row) ?: return false
            BoundKey(
                queueId = bound["queue_id"] ?: return false,
                trackId = bound["queue_track_id"] ?: return false,
                position = bound["queue_position"] ?: return false,
                shufflePosition = bound["queue_shuffle_position"]
                    ?: return false
            )
        }.sortedBy { it.queueId }
        val expected = context.items.map { item ->
            BoundKey(
                queueId = item.queueEntryId,
                trackId = item.track.id,
                position = item.queuePosition.toLong(),
                shufflePosition = item.shufflePosition.toLong()
            )
        }.sortedBy { it.queueId }
        return actual == expected
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

            // Match the same erased generated-Room binder proof used by the
            // queue entity-type resolver. Prefer a binder declared directly
            // by the runtime adapter so inherited helper binders (for example
            // a second statement contract) do not create false ambiguity.
            val allBinders = methods.filter {
                it.declaringClass != Any::class.java &&
                    it.parameterCount == 2 &&
                    !it.parameterTypes[0].isPrimitive &&
                    it.returnType == java.lang.Void.TYPE &&
                    it.parameterTypes[1] == Any::class.java
            }
            val directBinders = allBinders.filter {
                it.declaringClass == owner.javaClass
            }
            val bind = directBinders.ifEmpty { allBinders }
                .singleOrNull() ?: return@mapNotNull null
            if (!bind.parameterTypes[0].isInterface) return@mapNotNull null
            bind.isAccessible = true

            val sqlValues = methods.filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.declaringClass != Any::class.java
            }.mapNotNull { method ->
                runCatching {
                    method.isAccessible = true
                    val sql = method.invoke(owner) as? String
                        ?: return@runCatching null
                    val columns = bindColumns(sql)
                        ?: return@runCatching null
                    Triple(method, sql, columns)
                }.getOrNull()
            }
            val sqlGroups = sqlValues.groupBy { (_, sql, _) ->
                normalizeSql(sql)
            }
            if (sqlGroups.size != 1) return@mapNotNull null
            val selected = sqlGroups.values.single()
                .sortedByDescending { (method, _, _) ->
                    method.declaringClass == owner.javaClass
                }
                .first()

            AdapterBinding(
                owner = owner,
                sqlMethod = selected.first,
                bindMethod = bind,
                columns = selected.third
            )
        }
        return candidates
            .distinctBy {
                it.owner.javaClass.name + "|" +
                    normalizeSql(
                        runCatching {
                            it.sqlMethod.isAccessible = true
                            it.sqlMethod.invoke(it.owner) as String
                        }.getOrDefault("")
                    ) + "|" +
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

    private fun normalizeSql(sql: String): String =
        sql.replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

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
