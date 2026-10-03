package io.github.alagga.gonesmart

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * GMMP 4.2.1 native queue mutation bridge.
 *
 * Read identity/order from the verified read-only Cursor bridge, then
 * correlate those values to GMMP's actual generated Queue DAO entity objects.
 * No obfuscated entity field is accepted until queue_id, song_id and
 * queue_position agree with the live database rows. Writes still go through
 * GMMP's generated DAO methods; no direct SQL mutation is performed.
 */
internal class GmmpQueueMutationBridge(
    private val autoDj: Any
) {
    companion object {
        private const val TAG = "GoneSmartQueue"
        private val reportedMutationShapes =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val reportedEntityFactoryShapes =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val reportedEntityTypeMappings =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private interface StatePositionBinding {
        val description: String
        fun read(): Int
        fun write(value: Int)
    }

    private class FieldStatePositionBinding(
        private val host: Any,
        private val field: Field
    ) : StatePositionBinding {
        override val description: String =
            host.javaClass.name + ".field:" + field.name

        override fun read(): Int =
            (field.get(host) as? Number)?.toInt()
                ?: error("GMMP current-position field became unavailable")

        override fun write(value: Int) {
            if (field.type == Integer.TYPE) {
                field.setInt(host, value)
            } else {
                field.set(host, value)
            }
            check(read() == value) {
                "GMMP current-position field write did not stick"
            }
        }
    }

    private class MethodStatePositionBinding(
        private val host: Any,
        private val getter: Method,
        private val setter: Method
    ) : StatePositionBinding {
        override val description: String =
            host.javaClass.name + ".method:" + getter.name +
                "->" + setter.name

        override fun read(): Int =
            (getter.invoke(host) as? Number)?.toInt()
                ?: error("GMMP current-position getter became unavailable")

        override fun write(value: Int) {
            setter.invoke(host, value)
            check(read() == value) {
                "GMMP current-position setter did not update getter"
            }
        }
    }

    private data class Resolved(
        val dao: Any,
        val rows: List<Any>,
        val queueId: Field,
        val trackId: Field,
        val position: Field,
        val update: Method,
        val delete: Method?,
        val statePosition: StatePositionBinding,
        val context: QueueContext
    )

    fun reverseQueue(): Int {
        val resolved = resolve(requireDelete = false)
        val original = resolved.rows.sortedBy {
            number(resolved.position, it).toInt()
        }
        if (original.size < 2) return original.size

        val oldPositions = original.map {
            number(resolved.position, it).toInt()
        }
        val oldIds = original.map {
            number(resolved.queueId, it).toLong()
        }
        require(oldIds.toSet().size == oldIds.size) {
            "GMMP native queue IDs are not unique"
        }
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("GMMP current queue entry unavailable")
        val currentId = current.queueEntryId
        val newOrder = original.reversed()
        val newCurrentIndex = newOrder.indexOfFirst {
            number(resolved.queueId, it).toLong() == currentId
        }
        require(newCurrentIndex >= 0)
        val newCurrentPosition = oldPositions[newCurrentIndex]
        val oldState = resolved.statePosition.read()

        try {
            newOrder.forEachIndexed { index, row ->
                setInt(resolved.position, row, oldPositions[index])
            }
            resolved.update.invoke(
                resolved.dao,
                ArrayList(newOrder)
            )
            resolved.statePosition.write(newCurrentPosition)
            val verified = GmmpQueueReader().read(autoDj)
                ?: error("Queue verification unavailable")
            val verifyIds = verified.items
                .sortedBy { it.queuePosition }
                .map { it.queueEntryId }
            val verifyCurrent = verified.items.singleOrNull {
                it.state == QueueItemState.CURRENT
            }?.queueEntryId
            require(
                verifyIds == oldIds.reversed() &&
                    verifyCurrent == currentId
            ) {
                "GMMP 4.2.1 queue verification failed"
            }
            Log.i(
                TAG,
                "QUEUE MUTATION | reverse verified | size=" +
                    original.size + " | currentId=" + currentId +
                    " | newPosition=" + newCurrentPosition
            )
            return original.size
        } catch (failure: Throwable) {
            runCatching {
                original.forEachIndexed { index, row ->
                    setInt(resolved.position, row, oldPositions[index])
                }
                resolved.update.invoke(
                    resolved.dao,
                    ArrayList(original)
                )
                resolved.statePosition.write(oldState)
            }.onFailure {
                failure.addSuppressed(it)
            }
            throw failure
        }
    }

    fun isolateCurrentTrack(selectedTrackId: Long): Boolean {
        val resolved = resolve(requireDelete = true)
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: return false
        if (current.track.id != selectedTrackId) return false
        if (resolved.rows.size == 1) return true

        val currentModel = resolved.rows.singleOrNull {
            number(resolved.queueId, it).toLong() ==
                current.queueEntryId
        } ?: return false
        val stale = resolved.rows.filter { it !== currentModel }
        val delete = resolved.delete ?: return false
        val component = delete.parameterTypes.single().componentType
        val staleArray = java.lang.reflect.Array.newInstance(
            component,
            stale.size
        )
        stale.forEachIndexed { index, value ->
            java.lang.reflect.Array.set(staleArray, index, value)
        }

        // Delete stale rows first. If the following position normalization
        // fails, the currently playing native row is still retained.
        delete.invoke(resolved.dao, staleArray)
        setInt(resolved.position, currentModel, 1)
        resolved.update.invoke(
            resolved.dao,
            arrayListOf(currentModel)
        )
        resolved.statePosition.write(1)

        val verified = GmmpQueueReader().read(autoDj) ?: return false
        val only = verified.items.singleOrNull() ?: return false
        val ok = only.track.id == selectedTrackId &&
            only.queueEntryId == current.queueEntryId &&
            only.state == QueueItemState.CURRENT
        if (ok) {
            Log.i(
                TAG,
                "QUEUE MUTATION | seed isolation verified | track=" +
                    selectedTrackId + " | queueId=" + only.queueEntryId
            )
        }
        return ok
    }

    private fun resolve(requireDelete: Boolean): Resolved {
        val context = GmmpQueueReader().read(autoDj)
            ?: error("GMMP queue Cursor mapping unavailable")
        val dao = objectField(autoDj, "q")
            ?: error("GMMP 4.2.1 Queue DAO unavailable")
        // GMMP 4.2.1 exposes generated Room DAO boundaries through
        // inherited interfaces as well as concrete class methods. This is the
        // same reflection shape that moved f94.q(p94) out of a
        // class/superclass-only scan. Interface Method.invoke still dispatches
        // on the concrete DAO instance; failed contracts are ignored below.
        val methods = GmmpReflectionPolicy.callableMethods(dao.javaClass)
        // r24 proved that the nearest queue-specific API array type (ww3)
        // is not the queue_table writer entity. Prefer the generated Room
        // adapter itself: its queue_table SQL + erased bind(Object) bridge
        // can reveal the actual model class without executing SQL.
        val adapterEntity =
            NativeQueueEntityAdapterTypeResolver.resolve(dao)
        val entityTypeHint =
            adapterEntity?.modelClass
                ?: NativeQueueEntityTypeResolver.resolve(
                    dao.javaClass,
                    methods
                )
        if (adapterEntity != null) {
            val key = dao.javaClass.name + "|" +
                adapterEntity.modelClass.name
            if (reportedEntityTypeMappings.add(key)) {
                Log.i(
                    TAG,
                    "QUEUE ENTITY TYPE | source=generated-room-adapter" +
                        " | model=" + adapterEntity.modelClass.name +
                        " | evidence=" + adapterEntity.evidence
                )
            }
        }
        val listReaders = methods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                java.util.List::class.java
                    .isAssignableFrom(it.returnType)
        }
        val listCandidates =
            arrayListOf<Pair<String, List<Any>>>()
        listReaders.forEach { method ->
            runCatching {
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (method.invoke(dao) as? List<Any?>)
                    ?.filterNotNull()
                    ?.map { it }
            }.getOrNull()?.takeIf {
                it.size == context.items.size && it.isNotEmpty()
            }?.let { rows ->
                listCandidates +=
                    (method.declaringClass.name + "." + method.name +
                        ":direct") to rows
            }
        }

        // GMMP 4.2.1 d85 no longer exposes the native Queue entity snapshot
        // as a direct List. Its no-arg W1/X1 boundaries return R8-renamed
        // reactive carriers. Materialize each carrier read-only and keep
        // bounded partial row emissions even when no safe obfuscated entity
        // hint exists. Full queue_id/song_id/queue_position correlation below
        // remains mandatory before any writer is eligible.
        val allReactiveReaders = if (listCandidates.isEmpty()) {
            methods.filter {
                !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType != java.lang.Void.TYPE &&
                !it.returnType.isPrimitive &&
                !java.util.List::class.java
                    .isAssignableFrom(it.returnType) &&
                it.declaringClass != Any::class.java &&
                it.returnType != String::class.java &&
                it.returnType != Class::class.java
            }
        } else {
            emptyList()
        }
        // Prefer generated DAO implementation boundaries. In the r18 shape
        // these are exactly d85.W1()/X1(); inherited ys3 helpers are not
        // query ownership evidence and must not be invoked speculatively.
        val reactiveReaders =
            allReactiveReaders.filter {
                it.declaringClass == dao.javaClass
            }.ifEmpty {
                allReactiveReaders
            }
        val unresolvedCarrierShapes = arrayListOf<String>()
        val partialReactiveRows =
            arrayListOf<Pair<String, List<Any>>>()
        reactiveReaders.take(6).forEach { method ->
            val source = runCatching {
                method.isAccessible = true
                method.invoke(dao)
            }.getOrNull() ?: return@forEach

            // r31: r30 intentionally removed the disproven ww3/predicate
            // entity hint, but the old bridge accidentally disabled partial
            // aggregation at the same time. Read each carrier once with
            // allowPartial=true and group native rows later by their actual
            // runtime model class. Nothing becomes writable until Cursor
            // identity correlation succeeds.
            val snapshot = NativeReactiveListReader.read(
                source = source,
                expectedRows = context.items.size,
                timeoutMs = 650L,
                expectedModelClass = null,
                allowPartial = true
            )
            if (snapshot != null) {
                val sourceName =
                    method.declaringClass.name + "." + method.name +
                        "->" + snapshot.boundary
                if (snapshot.rows.size == context.items.size &&
                    cursorCorrelates(snapshot.rows, context)
                ) {
                    listCandidates +=
                        (sourceName + ":cursor-correlated") to snapshot.rows
                } else if (snapshot.rows.size < context.items.size) {
                    partialReactiveRows += sourceName to snapshot.rows
                } else {
                    unresolvedCarrierShapes +=
                        method.name + "->" + source.javaClass.name +
                            "{rows=" + snapshot.rows.size +
                            ";model=" + snapshot.rows.firstOrNull()
                                ?.javaClass?.name.orEmpty() + "}"
                }
            } else {
                val carrierMethods =
                    GmmpReflectionPolicy.callableMethods(source.javaClass)
                        .filter {
                            !Modifier.isStatic(it.modifiers) &&
                                it.parameterCount <= 1 &&
                                it.declaringClass != Any::class.java
                        }
                        .take(24)
                        .joinToString(",") {
                            it.name + "(" +
                                it.parameterTypes.joinToString(",") { p ->
                                    p.name
                                } +
                                "):" + it.returnType.name
                        }
                unresolvedCarrierShapes +=
                    method.name + "->" + source.javaClass.name +
                        "{" + carrierMethods + "}"
            }
        }

        // Generated fake-binder reconstruction remains a secondary fallback,
        // but only after the real read carrier did not expose a fully
        // Cursor-correlated entity set. When r25 resolves the actual entity
        // from d85's queue_table adapter, this path can safely reconstruct
        // that model from the already-verified Cursor values. The old ww3
        // API witness remains only a final compatibility fallback.
        if (listCandidates.isEmpty() && entityTypeHint != null) {
            val reconstructed = NativeQueueEntityReconstructor.reconstruct(
                dao = dao,
                modelClass = entityTypeHint,
                context = context
            )
            if (reconstructed != null &&
                cursorCorrelates(reconstructed.rows, context)
            ) {
                listCandidates +=
                    reconstructed.boundary to reconstructed.rows
                Log.i(
                    TAG,
                    "QUEUE ENTITY FACTORY | model=" +
                        entityTypeHint.name +
                        " | rows=" + reconstructed.rows.size +
                        " | proof=" + reconstructed.boundary
                )
            } else {
                val key = dao.javaClass.name + "|" + entityTypeHint.name
                if (reportedEntityFactoryShapes.add(key)) {
                    Log.w(
                        TAG,
                        "QUEUE ENTITY FACTORY | unresolved | " +
                            NativeQueueEntityReconstructor.diagnosticShape(
                                dao,
                                entityTypeHint
                            )
                    )
                }
            }
        }

        // W1/X1 may expose different partial views of the same native Queue
        // stream. Aggregate by the emitted runtime model class rather than by
        // an obfuscated type hint. A candidate is admitted only if the helper
        // reaches exactly the Cursor row count and cursorCorrelates() proves
        // queue_id/song_id/queue_position one-to-one for the full set.
        if (partialReactiveRows.isNotEmpty()) {
            listCandidates += NativeQueuePartialRowAggregator.aggregate(
                partials = partialReactiveRows,
                expectedRows = context.items.size,
                fingerprint = { row ->
                    nativeRowFingerprint(listOf(row))
                },
                correlates = { rows ->
                    cursorCorrelates(rows, context)
                }
            )
        }

        val distinctCandidates = listCandidates
            .filter { (_, rows) -> cursorCorrelates(rows, context) }
            .distinctBy { (_, rows) ->
                nativeRowFingerprint(rows)
            }
        val chosenRows = distinctCandidates.singleOrNull()
        if (chosenRows == null) {
            reportMutationShape(dao, methods, context.items.size)
            if (partialReactiveRows.isNotEmpty()) {
                Log.w(
                    TAG,
                    "QUEUE REACTIVE PARTIAL | " +
                        partialReactiveRows.joinToString(";") { (source, rows) ->
                            source + "#" + rows.size + "[" +
                                rows.joinToString(",") { it.javaClass.name } +
                                "]"
                        }
                )
            }
            if (unresolvedCarrierShapes.isNotEmpty()) {
                Log.w(
                    TAG,
                    "QUEUE REACTIVE SHAPE | " +
                        unresolvedCarrierShapes.joinToString(";")
                )
            }
            error(
                "GMMP native queue entity reader is not unique: " +
                    "entityHint=" +
                    (entityTypeHint?.name ?: "none") + "; " +
                    distinctCandidates.joinToString(",") { (source, rows) ->
                        source + "#" + rows.size
                    }.ifBlank {
                        "none; callableReaders=" +
                            (
                                listReaders.map {
                                    it.declaringClass.name + "." + it.name
                                } +
                                    reactiveReaders.take(8).map {
                                        it.declaringClass.name + "." +
                                            it.name + "->" +
                                            it.returnType.name
                                    }
                                ).joinToString(",").ifBlank { "none" }
                    }
            )
        }
        val rows = chosenRows.second
        val modelClass = rows.first().javaClass
        require(rows.all(modelClass::isInstance))

        val fields = hierarchyFields(modelClass).filter {
            it.type == Integer.TYPE ||
                it.type == Integer::class.java ||
                it.type == java.lang.Long.TYPE ||
                it.type == java.lang.Long::class.java
        }.onEach { it.isAccessible = true }

        val expectedQueueIds =
            context.items.map { it.queueEntryId }.sorted()
        val queueIdCandidates = fields.filter { field ->
            rows.mapNotNull {
                runCatching {
                    (field.get(it) as? Number)?.toLong()
                }.getOrNull()
            }.sorted() == expectedQueueIds
        }
        val queueId = queueIdCandidates.singleOrNull()
            ?: error(
                "GMMP queue_id field is not unique: " +
                    queueIdCandidates.joinToString(",") { it.name }
            )

        val contextByQueueId =
            context.items.associateBy { it.queueEntryId }
        fun aligned(
            label: String,
            expected: (QueueItemInfo) -> Long
        ): Field {
            val matches = fields.filter { field ->
                field != queueId && rows.all { row ->
                    val id = number(queueId, row).toLong()
                    val item = contextByQueueId[id]
                        ?: return@all false
                    number(field, row).toLong() == expected(item)
                }
            }
            return matches.singleOrNull()
                ?: error(
                    "GMMP $label field is not unique: " +
                        matches.joinToString(",") { it.name }
                )
        }

        val trackId = aligned("track_id") { it.track.id }
        val position = aligned("queue_position") {
            it.queuePosition.toLong()
        }
        require(trackId != position)

        val updateCandidates = methods.filter {
            it.parameterCount == 1 &&
                java.util.List::class.java
                    .isAssignableFrom(it.parameterTypes[0]) &&
                it.returnType == java.lang.Void.TYPE
        }
        val update = updateCandidates.singleOrNull {
            it.name == "O0"
        } ?: updateCandidates.singleOrNull()
            ?: error(
                "GMMP Queue DAO update is not unique: " +
                    updateCandidates.joinToString(",") { it.name }
            )
        update.isAccessible = true

        val deleteCandidates = methods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0].isArray &&
                it.parameterTypes[0].componentType
                    .isAssignableFrom(modelClass) &&
                it.returnType == java.lang.Void.TYPE
        }
        val ownedDeleteCandidates = deleteCandidates.filter {
            it.declaringClass == dao.javaClass
        }
        val delete = (
            // In 4.2.1 the generated d85 implementation owns one concrete
            // array writer (P0 in the observed build). Prefer exact DAO
            // ownership over the old inherited 4.2.0 name "O".
            ownedDeleteCandidates.singleOrNull()
                ?: deleteCandidates.singleOrNull { it.name == "O" }
                ?: deleteCandidates.singleOrNull()
            )?.apply { isAccessible = true }
        if (requireDelete && delete == null) {
            error(
                "GMMP Queue DAO delete is not unique: " +
                    deleteCandidates.joinToString(",") { it.name }
            )
        }

        val statePosition = resolveStatePosition(
            context.currentQueuePosition
        )

        Log.i(
            TAG,
            "QUEUE MUTATION MAPPING | dao=" + dao.javaClass.name +
                " | model=" + modelClass.name +
                " | entityHint=" +
                (entityTypeHint?.name ?: "none") +
                " | readRows=" + rows.size +
                " | queueId=" + queueId.name +
                " | trackId=" + trackId.name +
                " | position=" + position.name +
                " | update=" + update.name +
                " | delete=" + (delete?.name ?: "none") +
                " | state=" + statePosition.description +
                " | reader=" + chosenRows.first
        )
        return Resolved(
            dao,
            rows,
            queueId,
            trackId,
            position,
            update,
            delete,
            statePosition,
            context
        )
    }

    private fun resolveStatePosition(
        currentQueuePosition: Int
    ): StatePositionBinding {
        fun matchingFields(host: Any): List<Field> =
            hierarchyFields(host.javaClass).filter {
                it.type == Integer.TYPE ||
                    it.type == Integer::class.java
            }.onEach { it.isAccessible = true }.filter {
                runCatching {
                    (it.get(host) as? Number)?.toInt() ==
                        currentQueuePosition
                }.getOrDefault(false)
            }

        fun matchingMethod(host: Any): MethodStatePositionBinding? {
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
                    (method.invoke(host) as? Number)?.toInt() ==
                        currentQueuePosition
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
            val getter = getters.singleOrNull() ?: return null
            val setter = setters.singleOrNull() ?: return null
            getter.isAccessible = true
            setter.isAccessible = true
            return MethodStatePositionBinding(host, getter, setter)
        }

        // r20 device evidence repeatedly proves qr.t -> ur.method:b as the
        // 4.2.1 current queue-position pointer. Resolve that state host first,
        // before generic correlation can confuse TrackDao kr.G1 with a queue
        // position that happens to have the same integer value.
        objectField(autoDj, "t")?.let { state ->
            matchingFields(state).singleOrNull()?.let {
                return FieldStatePositionBinding(state, it)
            }
            matchingMethod(state)?.let { return it }
        }

        // Preserve the earlier 4.2.1 dx3 field path as fallback.
        objectField(autoDj, "p")?.let { fast ->
            matchingFields(fast).singleOrNull()?.let {
                return FieldStatePositionBinding(fast, it)
            }
            matchingMethod(fast)?.let { return it }
        }

        val hosts = hierarchyFields(autoDj.javaClass)
            .mapNotNull { ownerField ->
                ownerField.isAccessible = true
                val host = runCatching {
                    ownerField.get(autoDj)
                }.getOrNull() ?: return@mapNotNull null
                if (!isStateSignalHost(host)) return@mapNotNull null
                ownerField.name to host
            }
            .distinctBy { (_, host) -> System.identityHashCode(host) }

        val fieldBindings = hosts.mapNotNull { (_, host) ->
            matchingFields(host).singleOrNull()?.let {
                FieldStatePositionBinding(host, it)
            }
        }
        if (fieldBindings.size == 1) return fieldBindings.single()

        // Generic fallback only after the proven t/p state hosts failed.
        val methodBindings = hosts.mapNotNull { (_, host) ->
            matchingMethod(host)
        }
        if (methodBindings.size == 1) return methodBindings.single()

        reportStateShape(
            currentQueuePosition,
            hosts,
            fieldBindings,
            methodBindings
        )
        error(
            "GMMP current-position state binding is not unique" +
                " | fields=" + fieldBindings.joinToString(",") {
                    it.description
                }.ifBlank { "none" } +
                " | methods=" + methodBindings.joinToString(",") {
                    it.description
                }.ifBlank { "none" }
        )
    }

    private fun isStateSignalHost(value: Any): Boolean {
        val name = value.javaClass.name
        if (name.startsWith("java.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.") ||
            value is java.util.Collection<*> ||
            value is java.util.concurrent.Executor
        ) return false
        val fields = hierarchyFields(value.javaClass)
        return fields.none { field ->
            val declaredDatabase =
                field.type.name ==
                    "gonemad.gmmp.data.database.GMDatabase" ||
                    generateSequence<Class<*>>(field.type) { c -> c.superclass }
                        .any { c -> c.name == "androidx.room.RoomDatabase" }
            if (declaredDatabase) {
                true
            } else {
                val nested = runCatching {
                    field.isAccessible = true
                    field.get(value)
                }.getOrNull()
                nested != null &&
                    (
                        nested.javaClass.name ==
                            "gonemad.gmmp.data.database.GMDatabase_Impl" ||
                            generateSequence<Class<*>>(
                                nested.javaClass
                            ) { c -> c.superclass }.any { c ->
                                c.name == "androidx.room.RoomDatabase"
                            }
                    )
            }
        }
    }

    private fun reportStateShape(
        currentQueuePosition: Int,
        hosts: List<Pair<String, Any>>,
        fieldBindings: List<StatePositionBinding>,
        methodBindings: List<StatePositionBinding>
    ) {
        val key = "state|" + autoDj.javaClass.name + "|" +
            currentQueuePosition
        if (!reportedMutationShapes.add(key)) return
        val details = hosts.take(8).joinToString(";") { (ownerField, host) ->
            val methods = GmmpReflectionPolicy.callableMethods(host.javaClass)
                .filter { !Modifier.isStatic(it.modifiers) }
            val readers = methods.filter {
                it.parameterCount == 0 &&
                    it.name != "hashCode" &&
                    (
                        it.returnType == Integer.TYPE ||
                            it.returnType == Integer::class.java
                    )
            }.mapNotNull { method ->
                runCatching {
                    method.isAccessible = true
                    val value = (method.invoke(host) as? Number)?.toInt()
                        ?: return@runCatching null
                    method.name + "=" + value
                }.getOrNull()
            }
            val writers = methods.filter {
                it.parameterCount == 1 &&
                    (
                        it.parameterTypes[0] == Integer.TYPE ||
                            it.parameterTypes[0] == Integer::class.java
                    ) &&
                    it.returnType == java.lang.Void.TYPE
            }.joinToString(",") { it.name }
            ownerField + "->" + host.javaClass.name +
                "{read=" + readers.joinToString(",").ifBlank { "none" } +
                ";write=" + writers.ifBlank { "none" } + "}"
        }
        Log.w(
            TAG,
            "QUEUE MUTATION STATE SHAPE | current=" +
                currentQueuePosition +
                " | hosts=" + details.ifBlank { "none" } +
                " | fieldCandidates=" +
                fieldBindings.joinToString(",") { it.description }
                    .ifBlank { "none" } +
                " | methodCandidates=" +
                methodBindings.joinToString(",") { it.description }
                    .ifBlank { "none" }
        )
    }

    private fun reportMutationShape(
        dao: Any,
        methods: List<Method>,
        expectedRows: Int
    ) {
        val key = dao.javaClass.name + "|" + expectedRows
        if (!reportedMutationShapes.add(key)) return
        val noArg = methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        }.take(40).joinToString(",") {
            it.declaringClass.name + "." + it.name +
                "():" + it.returnType.name
        }
        val listWrites = methods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                (
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) ||
                    it.parameterTypes[0].isArray
                )
        }.take(40).joinToString(",") {
            it.declaringClass.name + "." + it.name +
                "(" + it.parameterTypes[0].name + "):" +
                it.returnType.name
        }
        Log.w(
            TAG,
            "QUEUE MUTATION SHAPE | dao=" + dao.javaClass.name +
                " | expectedRows=" + expectedRows +
                " | noArg=" + noArg.ifBlank { "none" } +
                " | writers=" + listWrites.ifBlank { "none" }
        )
    }

    private fun cursorCorrelates(
        rows: List<Any>,
        context: QueueContext
    ): Boolean {
        if (rows.size != context.items.size || rows.isEmpty()) return false
        val model = rows.first().javaClass
        if (!rows.all(model::isInstance)) return false

        val fields = hierarchyFields(model).filter {
            it.type == Integer.TYPE ||
                it.type == Integer::class.java ||
                it.type == java.lang.Long.TYPE ||
                it.type == java.lang.Long::class.java
        }.onEach { it.isAccessible = true }
        if (fields.size < 3) return false

        val expectedQueueIds = context.items
            .map { it.queueEntryId }
            .sorted()
        val queueIdCandidates = fields.filter { field ->
            rows.mapNotNull { row ->
                runCatching {
                    (field.get(row) as? Number)?.toLong()
                }.getOrNull()
            }.sorted() == expectedQueueIds
        }
        if (queueIdCandidates.isEmpty()) return false

        val byQueueId = context.items.associateBy { it.queueEntryId }
        val mappings = arrayListOf<Triple<Field, Field, Field>>()
        queueIdCandidates.forEach { queueId ->
            val trackIds = fields.filter { field ->
                field != queueId && rows.all { row ->
                    val id = runCatching {
                        (queueId.get(row) as? Number)?.toLong()
                    }.getOrNull() ?: return@all false
                    val item = byQueueId[id] ?: return@all false
                    runCatching {
                        (field.get(row) as? Number)?.toLong()
                    }.getOrNull() == item.track.id
                }
            }
            val positions = fields.filter { field ->
                field != queueId && rows.all { row ->
                    val id = runCatching {
                        (queueId.get(row) as? Number)?.toLong()
                    }.getOrNull() ?: return@all false
                    val item = byQueueId[id] ?: return@all false
                    runCatching {
                        (field.get(row) as? Number)?.toLong()
                    }.getOrNull() == item.queuePosition.toLong()
                }
            }
            trackIds.forEach { track ->
                positions.forEach { position ->
                    if (track != position) {
                        mappings += Triple(queueId, track, position)
                    }
                }
            }
        }
        return mappings.distinctBy {
            it.first.name + "|" + it.second.name + "|" + it.third.name
        }.size == 1
    }

    private fun nativeRowFingerprint(rows: List<Any>): String {
        val first = rows.firstOrNull() ?: return "empty"
        val model = first.javaClass
        if (!rows.all(model::isInstance)) {
            return rows.joinToString("|") { it.javaClass.name }
        }
        val numeric = hierarchyFields(model).filter {
            it.type == Integer.TYPE ||
                it.type == Integer::class.java ||
                it.type == java.lang.Long.TYPE ||
                it.type == java.lang.Long::class.java
        }.onEach { it.isAccessible = true }
            .sortedBy { it.declaringClass.name + "|" + it.name }
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

    private fun objectField(target: Any, name: String): Any? =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .mapNotNull { owner ->
                runCatching {
                    owner.getDeclaredField(name).apply {
                        isAccessible = true
                    }
                }.getOrNull()
            }
            .firstOrNull()
            ?.let { runCatching { it.get(target) }.getOrNull() }

    private fun number(field: Field, target: Any): Number =
        field.get(target) as? Number
            ?: error("GMMP queue numeric field became unavailable")

    private fun setInt(field: Field, target: Any, value: Int) {
        if (field.type == Integer.TYPE) {
            field.setInt(target, value)
        } else {
            field.set(target, value)
        }
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
