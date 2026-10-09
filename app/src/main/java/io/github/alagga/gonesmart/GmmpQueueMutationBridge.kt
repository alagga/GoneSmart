package io.github.alagga.gonesmart

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Native queue mutation bridge for GMMP 4.2.1.
 *
 * Discovery is deliberately split into two trust levels:
 * 1. Queue identity/order is read only through [GmmpQueueReader]'s verified
 *    Cursor path.
 * 2. A writable native entity model is accepted only from a generated Room
 *    adapter whose OWN SQL names queue_table. The adapter binder is probed
 *    without a real SQLite statement and the resulting entity mapping must
 *    correlate one-to-one with the live Cursor before any writer is invoked.
 *
 * Track Mix is deliberately different from Queue Flip: after native Play,
 * GMMP 4.2.1 may already have prepared the next AudioSource. Deleting or
 * renumbering Room rows behind that playback state can make the Queue UI and
 * the actually decoded next track disagree. Track Mix therefore clears its
 * upcoming queue through GMMP's own public CLEAR_QUEUE command and only reads
 * the database back as a postcondition. Direct Room mutation remains reserved
 * for boundaries such as Queue Flip where the complete native writer contract
 * has already been accepted.
 */
internal class GmmpQueueMutationBridge(
    private val autoDj: Any,
    private val verifiedPositionWriter: NativeQueuePositionWriter? = null
) {
    companion object {
        private const val TAG = "GoneSmartQueue"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val COMMAND_CLEAR_QUEUE = "gonemad.gmmp.command.CLEAR_QUEUE"
        private const val NATIVE_CLEAR_TIMEOUT_MS = 3_000L
        private val reportedShapes =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val reportedEntityMappings =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private val reportedTrackMixNativeOwnership =
            java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun typedEntityArray(
            modelClass: Class<*>,
            values: List<Any>
        ): Any {
            require(values.all(modelClass::isInstance)) {
                "Native queue delete rows do not share the verified entity type"
            }
            val result = java.lang.reflect.Array.newInstance(
                modelClass,
                values.size
            )
            values.forEachIndexed { index, value ->
                java.lang.reflect.Array.set(result, index, value)
            }
            return result
        }
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

    private class ExternalStatePositionBinding(
        private val writer: NativeQueuePositionWriter
    ) : StatePositionBinding {
        override val description: String = writer.description

        override fun read(): Int = writer.read()
            ?: error("Passively verified GMMP current-position signal unavailable")

        override fun write(value: Int) {
            check(writer.write(value)) {
                "Passively verified GMMP current-position writer failed"
            }
            check(read() == value) {
                "Passively verified GMMP current-position write did not stick"
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
        val statePosition: StatePositionBinding?,
        val context: QueueContext
    )

    fun reverseQueue(): Int {
        val resolved = resolve(
            requireDelete = false,
            requireStatePosition = true
        )
        val statePosition = resolved.statePosition
            ?: error("GMMP current-position writer unavailable")
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
        val oldState = statePosition.read()

        try {
            newOrder.forEachIndexed { index, row ->
                setInt(resolved.position, row, oldPositions[index])
            }
            resolved.update.invoke(
                resolved.dao,
                ArrayList(newOrder)
            )
            statePosition.write(newCurrentPosition)

            val verified = GmmpQueueReader().read(
                autoDj,
                statePosition.read()
            ) ?: error("Queue verification unavailable")
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
                statePosition.write(oldState)
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    /**
     * Track Mix seed isolation on 4.2.1 must go through GMMP itself. Static
     * inspection of the 4.2.1 APK confirms the unchanged public command
     * `gonemad.gmmp.command.CLEAR_QUEUE`, which is mapped by GMMP and delivered
     * into MusicService. This allows GMMP to invalidate its playback/prefetch
     * state together with the visible queue instead of GoneSmart changing only
     * Room rows after a next AudioSource may already have been prepared.
     */
    fun isolateCurrentTrack(selectedTrackId: Long): Boolean {
        fun read(): QueueContext? = GmmpQueueReader().read(
            autoDj,
            verifiedPositionWriter?.read()
        )

        val before = read() ?: return false
        val current = before.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: return false
        if (current.track.id != selectedTrackId) return false
        if (before.items.size == 1) return true

        val context = currentApplicationContext() ?: run {
            Log.e(TAG, "TRACK MIX NATIVE CLEAR | application Context unavailable")
            return false
        }
        context.sendBroadcast(
            Intent(COMMAND_CLEAR_QUEUE).setPackage(GMMP_PACKAGE)
        )
        Log.i(
            TAG,
            "TRACK MIX NATIVE CLEAR | command=sent | selectedTrack=" +
                selectedTrackId + " | queueId=" + current.queueEntryId +
                " | previousSize=" + before.items.size
        )

        val deadline = SystemClock.elapsedRealtime() + NATIVE_CLEAR_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50L)
            val verified = read() ?: continue
            val only = verified.items.singleOrNull() ?: continue
            if (
                only.track.id == selectedTrackId &&
                only.queueEntryId == current.queueEntryId &&
                only.state == QueueItemState.CURRENT
            ) {
                Log.i(
                    TAG,
                    "TRACK MIX NATIVE CLEAR | verified=true | track=" +
                        selectedTrackId + " | queueId=" + only.queueEntryId +
                        " | position=" + only.queuePosition
                )
                return true
            }
        }

        val final = read()
        Log.w(
            TAG,
            "TRACK MIX NATIVE CLEAR | verified=false | selectedTrack=" +
                selectedTrackId + " | finalSize=" +
                (final?.items?.size ?: -1) + " | finalCurrent=" +
                (final?.items?.singleOrNull {
                    it.state == QueueItemState.CURRENT
                }?.track?.id ?: -1L)
        )
        return false
    }

    /**
     * Track Mix no longer rewrites queue positions after native refill on
     * 4.2.1. GMMP owns those positions and its already prepared playback
     * source. Returning false tells the caller no GoneSmart reordering was
     * performed; native queue order remains authoritative.
     */
    fun normalizeQueuePositionsIfNeeded(): Boolean {
        reportNativeTrackMixOwnershipOnce()
        return false
    }

    /** See [normalizeQueuePositionsIfNeeded]. */
    fun rebaseQueueToOneIfNeeded(): Boolean {
        reportNativeTrackMixOwnershipOnce()
        return false
    }

    private fun reportNativeTrackMixOwnershipOnce() {
        if (reportedTrackMixNativeOwnership.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "TRACK MIX QUEUE OWNERSHIP | native GMMP order retained | " +
                    "GoneSmart position normalization disabled"
            )
        }
    }

    private fun currentApplicationContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()

    private fun resolve(
        requireDelete: Boolean,
        requireStatePosition: Boolean
    ): Resolved {
        val absoluteHint = verifiedPositionWriter?.read()
        val context = GmmpQueueReader().read(autoDj, absoluteHint)
            ?: error("GMMP queue Cursor mapping unavailable")

        val daoResolution = NativeQueueDaoResolver.resolve(autoDj)
        if (daoResolution == null) {
            objectField(autoDj, "q")?.let { legacyCandidate ->
                reportRoomAdapterShape(
                    legacyCandidate,
                    GmmpReflectionPolicy.callableMethods(
                        legacyCandidate.javaClass
                    ),
                    context.items.size
                )
            }
            val key = "dao-discovery|" + autoDj.javaClass.name
            if (reportedShapes.add(key)) {
                Log.w(
                    TAG,
                    "QUEUE DAO DISCOVERY SHAPE | " +
                        NativeQueueDaoResolver.diagnosticShape(autoDj)
                )
            }
            error(
                "GMMP queue_table writer DAO is unresolved; " +
                    "reactive/query DAO discovery is disabled"
            )
        }

        val dao = daoResolution.dao
        val adapterEntity = daoResolution.entity
        val methods = GmmpReflectionPolicy.callableMethods(dao.javaClass)

        val typeKey = dao.javaClass.name + "|" + adapterEntity.modelClass.name
        if (reportedEntityMappings.add(typeKey)) {
            Log.i(
                TAG,
                "QUEUE DAO MAPPING | dao=" + dao.javaClass.name +
                    " | proof=queue_table-generated-adapter" +
                    " | evidence=" + daoResolution.evidence
            )
            Log.i(
                TAG,
                "QUEUE ENTITY TYPE | source=generated-room-adapter" +
                    " | model=" + adapterEntity.modelClass.name +
                    " | evidence=" + adapterEntity.evidence
            )
        }

        val reconstructed = NativeQueueEntityReconstructor.reconstruct(
            dao = dao,
            modelClass = adapterEntity.modelClass,
            context = context
        )
        if (reconstructed == null ||
            !cursorCorrelates(reconstructed.rows, context)
        ) {
            reportRoomAdapterShape(dao, methods, context.items.size)
            error(
                "GMMP queue_table Room entity reconstruction did not " +
                    "correlate with the read-only Cursor"
            )
        }

        val rows = reconstructed.rows
        val modelClass = rows.first().javaClass
        require(rows.all(modelClass::isInstance))

        val fields = numericFields(modelClass)
        val expectedQueueIds = context.items.map {
            it.queueEntryId
        }.sorted()
        val queueIdCandidates = fields.filter { field ->
            rows.mapNotNull { row ->
                runCatching {
                    (field.get(row) as? Number)?.toLong()
                }.getOrNull()
            }.sorted() == expectedQueueIds
        }
        val queueId = queueIdCandidates.singleOrNull()
            ?: error(
                "GMMP queue_id field is not unique: " +
                    queueIdCandidates.joinToString(",") { it.name }
            )

        val contextByQueueId = context.items.associateBy { it.queueEntryId }
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
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                java.util.List::class.java
                    .isAssignableFrom(it.parameterTypes[0]) &&
                it.returnType == java.lang.Void.TYPE
        }
        val ownedUpdates = updateCandidates.filter {
            it.declaringClass == dao.javaClass
        }
        val update = ownedUpdates.singleOrNull { it.name == "O0" }
            ?: ownedUpdates.singleOrNull()
            ?: updateCandidates.singleOrNull { it.name == "O0" }
            ?: updateCandidates.singleOrNull()
            ?: error(
                "GMMP Queue DAO update is not unique: " +
                    updateCandidates.joinToString(",") { it.name }
            )
        update.isAccessible = true

        val deleteCandidates = methods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isArray &&
                it.parameterTypes[0].componentType
                    .isAssignableFrom(modelClass) &&
                it.returnType == java.lang.Void.TYPE
        }
        val ownedDeletes = deleteCandidates.filter {
            it.declaringClass == dao.javaClass
        }
        val delete = (
            ownedDeletes.singleOrNull()
                ?: deleteCandidates.singleOrNull { it.name == "O" }
                ?: deleteCandidates.singleOrNull()
            )?.apply { isAccessible = true }
        if (requireDelete && delete == null) {
            error(
                "GMMP Queue DAO delete is not unique: " +
                    deleteCandidates.joinToString(",") { it.name }
            )
        }

        val statePosition = if (requireStatePosition) {
            resolveStatePosition(context.currentQueuePosition)
        } else {
            null
        }

        Log.i(
            TAG,
            "QUEUE MUTATION MAPPING | dao=" + dao.javaClass.name +
                " | model=" + modelClass.name +
                " | readRows=" + rows.size +
                " | queueId=" + queueId.name +
                " | trackId=" + trackId.name +
                " | position=" + position.name +
                " | update=" + update.name +
                " | delete=" + (delete?.name ?: "none") +
                " | state=" +
                (statePosition?.description ?: "deferred-if-needed") +
                " | reader=" + reconstructed.boundary
        )

        return Resolved(
            dao = dao,
            rows = rows,
            queueId = queueId,
            trackId = trackId,
            position = position,
            update = update,
            delete = delete,
            statePosition = statePosition,
            context = context
        )
    }

    private fun reportRoomAdapterShape(
        dao: Any,
        methods: List<Method>,
        expectedRows: Int
    ) {
        val key = "room|" + dao.javaClass.name
        if (reportedShapes.add(key)) {
            Log.w(
                TAG,
                "QUEUE ROOM ADAPTER SHAPE | dao=" + dao.javaClass.name +
                    " | adapters=" +
                    NativeQueueRoomAdapterDiagnostics.describe(dao)
            )
        }
        reportMutationShape(dao, methods, expectedRows)
    }

    private fun resolveStatePosition(
        currentQueuePosition: Int
    ): StatePositionBinding {
        fun externalBinding(): StatePositionBinding? {
            val writer = verifiedPositionWriter ?: return null
            if (writer.read() != currentQueuePosition) return null
            return ExternalStatePositionBinding(writer)
        }

        externalBinding()?.let { return it }

        // r39 disproved qr.t/ur.b as playback queue_position. Diagnose only
        // the older, repeatedly correlated qr.p/dx3 owner here, but do not
        // invoke any of its apparent setters until a natural GMMP call has
        // passively verified one through NativeQueuePositionWriterObserver.
        objectField(autoDj, "p")?.let { state ->
            val analysis = NativeQueueStateAccessorPolicy.analyze(
                state,
                currentQueuePosition
            )
            if (analysis.hasReadEvidence) {
                val key = "current-pointer|field:p|" +
                    state.javaClass.name + "|" + currentQueuePosition
                if (reportedShapes.add(key)) {
                    Log.w(
                        TAG,
                        "QUEUE CURRENT POINTER SHAPE | source=field:p | " +
                            analysis.describe(currentQueuePosition)
                    )
                }
                error(
                    "GMMP writable current-position binding is unresolved " +
                        "for corrected playback reader " + state.javaClass.name +
                        "; passive native writer has not been observed yet"
                )
            }
        }

        error(
            "GMMP current-position playback reader is unresolved; " +
                "passive native writer discovery remains fail-closed"
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

        return hierarchyFields(value.javaClass).none { field ->
            val declaredDatabase =
                field.type.name ==
                    "gonemad.gmmp.data.database.GMDatabase" ||
                    generateSequence<Class<*>>(field.type) { it.superclass }
                        .any { it.name == "androidx.room.RoomDatabase" }
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
                            ) { it.superclass }.any {
                                it.name == "androidx.room.RoomDatabase"
                            }
                    )
            }
        }
    }

    private fun reportMutationShape(
        dao: Any,
        methods: List<Method>,
        expectedRows: Int
    ) {
        val key = "mutation|" + dao.javaClass.name + "|" + expectedRows
        if (!reportedShapes.add(key)) return

        val noArg = methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        }.take(40).joinToString(",") {
            it.declaringClass.name + "." + it.name +
                "():" + it.returnType.name
        }
        val writers = methods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                (
                    java.util.List::class.java.isAssignableFrom(
                        it.parameterTypes[0]
                    ) || it.parameterTypes[0].isArray
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
                " | writers=" + writers.ifBlank { "none" } +
                " | reactiveInvocation=disabled"
        )
    }

    private fun cursorCorrelates(
        rows: List<Any>,
        context: QueueContext
    ): Boolean {
        if (rows.size != context.items.size || rows.isEmpty()) return false
        val model = rows.first().javaClass
        if (!rows.all(model::isInstance)) return false
        val fields = numericFields(model)
        if (fields.size < 3) return false

        val expectedQueueIds = context.items.map {
            it.queueEntryId
        }.sorted()
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

    private fun numericFields(type: Class<*>): List<Field> =
        hierarchyFields(type).filter {
            it.type == Integer.TYPE ||
                it.type == Integer::class.java ||
                it.type == java.lang.Long.TYPE ||
                it.type == java.lang.Long::class.java
        }.onEach { it.isAccessible = true }

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
            ?.let { field ->
                runCatching { field.get(target) }.getOrNull()
            }

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
