package io.github.alagga.gonesmart

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class GmmpQueueSourceBatchBinding(
    val dao: Any,
    private val modelClass: Class<*>,
    private val trackIdField: Field,
    private val positionField: Field
) {
    data class GuardResult(
        val batchSize: Int,
        val currentPosition: Int,
        val changed: Int,
        val trackIdsByPosition: Map<Int, Long>
    )

    fun guardBeforeWrite(
        payload: Any?,
        selectedTrackId: Long,
        nativeFallbackTrackIds: List<Long>,
        expectedSize: Int
    ): GuardResult? {
        val rows = payloadRows(payload) ?: return null
        val entries = rows.map { row ->
            TrackMixZeroPoolPolicy.SourceBatchEntry(
                trackId = number(trackIdField, row).toLong(),
                position = number(positionField, row).toInt()
            )
        }
        val plan = TrackMixZeroPoolPolicy.sourceBatchGuardPlan(
            entries = entries,
            selectedTrackId = selectedTrackId,
            nativeFallbackTrackIds = nativeFallbackTrackIds,
            expectedSize = expectedSize
        ) ?: return null
        val rowsByPosition = rows.associateBy {
            number(positionField, it).toInt()
        }
        var changed = 0
        plan.trackIdsByPosition.forEach { (position, trackId) ->
            val row = rowsByPosition[position] ?: return null
            if (number(trackIdField, row).toLong() != trackId) {
                setLong(trackIdField, row, trackId)
                changed += 1
            }
        }
        check(plan.trackIdsByPosition.all { (position, trackId) ->
            rowsByPosition[position]?.let {
                number(trackIdField, it).toLong() == trackId
            } == true
        }) {
            "Native source batch did not retain guarded fallback identity"
        }
        return GuardResult(
            batchSize = rows.size,
            currentPosition = plan.currentPosition,
            changed = changed,
            trackIdsByPosition = plan.trackIdsByPosition
        )
    }

    private fun payloadRows(payload: Any?): List<Any>? {
        val raw: List<*> = when {
            payload is List<*> -> payload
            payload != null && payload.javaClass.isArray ->
                (0 until java.lang.reflect.Array.getLength(payload)).map { index ->
                    java.lang.reflect.Array.get(payload, index)
                }
            else -> return null
        }
        if (raw.isEmpty() || raw.any { it == null || !modelClass.isInstance(it) }) {
            return null
        }
        return raw.filterNotNull()
    }

    private fun number(field: Field, target: Any): Number =
        field.get(target) as? Number
            ?: error("GMMP queue batch numeric field became unavailable")

    private fun setLong(field: Field, target: Any, value: Long) {
        when (field.type) {
            java.lang.Long.TYPE -> field.setLong(target, value)
            java.lang.Long::class.java -> field.set(target, value)
            Integer.TYPE, Integer::class.java -> {
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                if (field.type == Integer.TYPE) field.setInt(target, value.toInt())
                else field.set(target, value.toInt())
            }
            else -> error("GMMP queue batch Track-ID field is not numeric")
        }
    }
}

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
 * Track Mix 4.2.1 preserves GMMP's already materialized current+1 row. The
 * accepted queue DAO update/delete writers retarget existing upcoming entities
 * in place and remove surplus rows only after current+1 has been verified.
 * CLEAR_QUEUE is intentionally not used: r28 proved it can make MusicService
 * cache a null next AudioSource before any post-clear refill runs.
 */
internal class GmmpQueueMutationBridge(
    private val autoDj: Any,
    private val verifiedPositionWriter: NativeQueuePositionWriter? = null,
    private val currentQueueEntryIdHint: Long? = null
) {
    companion object {
        private const val TAG = "GoneSmartQueue"
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

    fun sourceBatchBinding(): GmmpQueueSourceBatchBinding {
        val resolved = resolve(
            requireDelete = false,
            requireStatePosition = false
        )
        val modelClass = resolved.rows.firstOrNull()?.javaClass
            ?: error("Verified GMMP queue entity model is unavailable")
        check(resolved.rows.all(modelClass::isInstance)) {
            "Verified GMMP queue rows do not share one entity model"
        }
        return GmmpQueueSourceBatchBinding(
            dao = resolved.dao,
            modelClass = modelClass,
            trackIdField = resolved.trackId,
            positionField = resolved.position
        )
    }

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
     * Retarget verified existing 4.2.1 upcoming rows without ever removing
     * current+1. O0(List) updates the native entities first; typed native DAO
     * deletion runs only after the rewritten future slots verify.
     */
    fun retargetTrackMixUpcoming(
        selectedTrackId: Long,
        smartTrackIds: List<Long>,
        targetQueueSize: Int
    ): Boolean {
        val resolved = resolve(requireDelete = true, requireStatePosition = false)
        val entries = resolved.context.items.map {
            TrackMixPlan.NativeQueueEntry(
                queueId = it.queueEntryId,
                trackId = it.track.id,
                position = it.queuePosition
            )
        }
        val plan = TrackMixPlan.planNativeRetarget(
            entries = entries,
            currentPosition = resolved.context.currentQueuePosition,
            selectedTrackId = selectedTrackId,
            targetQueueSize = targetQueueSize,
            smartTrackIds = smartTrackIds
        )
        val rowById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val changed = plan.rewriteTrackIds.map { (queueId, trackId) ->
            val row = rowById[queueId]
                ?: error("Verified native Track Mix row disappeared")
            setLong(resolved.trackId, row, trackId)
            row
        }
        if (changed.isNotEmpty()) {
            resolved.update.invoke(resolved.dao, ArrayList(changed))
        }

        val interim = GmmpQueueReader().read(
            autoDj,
            verifiedPositionWriter?.read(),
            currentQueueEntryIdHint
        ) ?: error("Track Mix retarget interim verification unavailable")
        val interimById = interim.items.associateBy { it.queueEntryId }
        check(interim.items.singleOrNull { it.state == QueueItemState.CURRENT }
            ?.track?.id == selectedTrackId) {
            "Track Mix Current changed during retarget"
        }
        plan.rewriteTrackIds.forEach { (queueId, trackId) ->
            check(interimById[queueId]?.track?.id == trackId) {
                "Track Mix native update did not retarget queue entry $queueId"
            }
        }

        val removeRows = plan.removeEntryIds.map { queueId ->
            rowById[queueId] ?: error("Verified surplus Track Mix row disappeared")
        }
        if (removeRows.isNotEmpty()) {
            val delete = resolved.delete ?: error("Verified GMMP Queue DAO delete unavailable")
            delete.invoke(
                resolved.dao,
                typedEntityArray(resolved.rows.first().javaClass, removeRows)
            )
        }

        val verified = GmmpQueueReader().read(
            autoDj,
            verifiedPositionWriter?.read(),
            currentQueueEntryIdHint
        ) ?: error("Track Mix retarget final verification unavailable")
        val ordered = verified.items.sortedBy { it.queuePosition }
        val current = verified.items.singleOrNull { it.state == QueueItemState.CURRENT }
        check(current?.track?.id == selectedTrackId) {
            "Track Mix Current changed after retarget"
        }
        check(ordered.size == targetQueueSize) {
            "Track Mix retarget size mismatch: ${ordered.size}/$targetQueueSize"
        }
        check(ordered.map { it.track.id } == plan.expectedTrackIds) {
            "Track Mix retarget order/Track IDs failed verification"
        }
        Log.i(
            TAG,
            "TRACK MIX RETARGET | verified=true | currentEntry=" + plan.currentEntryId +
                " | smart=" + smartTrackIds.size +
                " | nativeRemainder=" + (targetQueueSize - 1 - smartTrackIds.size) +
                " | deleted=" + removeRows.size +
                " | currentPosition=" + verified.currentQueuePosition
        )
        return true
    }

    /**
     * R50 one-shot pre-source guard. GMMP has already naturally chosen and
     * materialized the zero-pool fallback Track IDs before this point. A late
     * Smart-source rebuild may overwrite queue_position CURRENT+1 just before
     * MusicService asks for that row. Rewrite only that existing slot back to
     * the exact first native fallback ID, verify it, and leave source deletion,
     * position rebasing and playback-state writes to the later settled repair.
     */
    fun guardTrackMixZeroPoolNextSource(
        selectedTrackId: Long,
        nextQueuePosition: Int,
        expectedTrackId: Long
    ): Boolean {
        require(selectedTrackId > 0L) { "Selected Track ID must be positive" }
        require(nextQueuePosition > 0) { "Next queue position must be positive" }
        require(expectedTrackId > 0L) { "Expected next Track ID must be positive" }

        val resolved = resolve(requireDelete = false, requireStatePosition = false)
        val selectedMatches = resolved.context.items.filter {
            it.track.id == selectedTrackId
        }
        require(selectedMatches.size == 1) {
            "Selected Track is not uniquely present at natural CURRENT+1 boundary"
        }
        val next = resolved.context.items.singleOrNull {
            it.queuePosition == nextQueuePosition
        } ?: error("Natural CURRENT+1 queue row is unavailable")
        val row = resolved.rows.singleOrNull {
            number(resolved.position, it).toInt() == nextQueuePosition
        } ?: error("Native CURRENT+1 entity is unavailable")
        require(number(resolved.queueId, row).toLong() == next.queueEntryId) {
            "Native CURRENT+1 entity does not match verified Cursor row"
        }

        val originalTrackId = number(resolved.trackId, row).toLong()
        if (originalTrackId == expectedTrackId) {
            Log.i(
                TAG,
                "TRACK MIX ZERO-POOL NEXT GUARD | verified=true | changed=false" +
                    " | position=$nextQueuePosition | track=$expectedTrackId"
            )
            return true
        }

        try {
            setLong(resolved.trackId, row, expectedTrackId)
            resolved.update.invoke(resolved.dao, arrayListOf(row))

            val verified = GmmpQueueReader().read(
                autoDj,
                verifiedPositionWriter?.read(),
                currentQueueEntryIdHint
            ) ?: error("Natural CURRENT+1 repair verification unavailable")
            val verifiedNext = verified.items.singleOrNull {
                it.queuePosition == nextQueuePosition
            } ?: error("Natural CURRENT+1 row disappeared during verification")
            check(verifiedNext.track.id == expectedTrackId) {
                "Natural CURRENT+1 Track ID did not update"
            }
            check(verified.items.count { it.track.id == selectedTrackId } == 1) {
                "Selected Track identity changed during CURRENT+1 repair"
            }
            Log.i(
                TAG,
                "TRACK MIX ZERO-POOL NEXT GUARD | verified=true | changed=true" +
                    " | position=$nextQueuePosition | previousTrack=$originalTrackId" +
                    " | track=$expectedTrackId"
            )
            return true
        } catch (failure: Throwable) {
            runCatching {
                setLong(resolved.trackId, row, originalTrackId)
                resolved.update.invoke(resolved.dao, arrayListOf(row))
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    /**
     * r36 zero-provider path. The selected row has already been proven by
     * queue_id (including any r35 exact-path rebind). Remove source history,
     * move that exact row to position 1 through a passively observed native
     * position writer, then remove every source follower. No Track ID is
     * rewritten and CLEAR_QUEUE remains forbidden.
     */
    fun isolateTrackMixZeroPoolSeed(selectedTrackId: Long): Boolean {
        val resolved = resolve(requireDelete = true, requireStatePosition = true)
        val state = resolved.statePosition
            ?: error("GMMP current-position writer unavailable")
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("Track Mix Current queue entry unavailable")
        currentQueueEntryIdHint?.let { expected ->
            require(current.queueEntryId == expected) {
                "Track Mix pinned Current queue entry changed"
            }
        }
        val entries = resolved.context.items.map { item ->
            TrackMixPlan.NativeQueueEntry(
                queueId = item.queueEntryId,
                trackId = item.track.id,
                position = item.queuePosition
            )
        }
        val plan = TrackMixZeroPoolPolicy.seedPlan(
            entries = entries,
            currentEntryId = current.queueEntryId,
            selectedTrackId = selectedTrackId
        )
        val rowsById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val currentRow = rowsById[plan.currentEntryId]
            ?: error("Pinned Track Mix row disappeared")
        val delete = resolved.delete
            ?: error("Verified GMMP Queue DAO delete unavailable")
        val modelClass = resolved.rows.first().javaClass

        fun deleteEntries(ids: List<Long>) {
            if (ids.isEmpty()) return
            val rows = ids.map { id ->
                rowsById[id] ?: error("Verified Track Mix row disappeared")
            }
            delete.invoke(
                resolved.dao,
                typedEntityArray(modelClass, rows)
            )
        }

        // Free position 1 while CURRENT still points at its original row.
        deleteEntries(plan.pastEntryIds)
        val originalPosition = plan.currentPosition
        try {
            if (originalPosition != 1) {
                setInt(resolved.position, currentRow, 1)
                resolved.update.invoke(
                    resolved.dao,
                    arrayListOf(currentRow)
                )
                state.write(1)
            }

            val moved = GmmpQueueReader().read(
                autoDj,
                state.read(),
                plan.currentEntryId
            ) ?: error("Zero-pool seed move verification unavailable")
            val movedCurrent = moved.items.singleOrNull {
                it.state == QueueItemState.CURRENT
            } ?: error("Zero-pool Current disappeared after rebase")
            check(
                movedCurrent.queueEntryId == plan.currentEntryId &&
                    movedCurrent.track.id == selectedTrackId &&
                    movedCurrent.queuePosition == 1 &&
                    moved.currentQueuePosition == 1
            ) {
                "Zero-pool Current rebase failed verification"
            }
        } catch (failure: Throwable) {
            runCatching {
                if (originalPosition != 1) {
                    setInt(resolved.position, currentRow, originalPosition)
                    resolved.update.invoke(
                        resolved.dao,
                        arrayListOf(currentRow)
                    )
                    state.write(originalPosition)
                }
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }

        deleteEntries(plan.futureEntryIds)
        val verified = GmmpQueueReader().read(
            autoDj,
            state.read(),
            plan.currentEntryId
        ) ?: error("Zero-pool seed isolation verification unavailable")
        val only = verified.items.singleOrNull()
            ?: error("Zero-pool seed isolation left extra native rows")
        check(
            only.queueEntryId == plan.currentEntryId &&
                only.track.id == selectedTrackId &&
                only.queuePosition == 1 &&
                verified.currentQueuePosition == 1 &&
                only.state == QueueItemState.CURRENT
        ) {
            "Zero-pool seed isolation did not preserve exact Current"
        }
        Log.i(
            TAG,
            "TRACK MIX ZERO-POOL SEED | verified=true" +
                " | currentEntry=" + plan.currentEntryId +
                " | oldPosition=" + originalPosition +
                " | newPosition=1" +
                " | deletedPast=" + plan.pastEntryIds.size +
                " | deletedSourceUpcoming=" + plan.futureEntryIds.size
        )
        return true
    }

    /**
     * Native qr.z owns the replacement Track IDs. Before refreshing GMMP's
     * next AudioSource, compact only their queue_position values to 1..N and
     * independently verify the exact pinned Current remains position 1.
     */
    fun rebaseTrackMixZeroPoolInitialQueue(
        selectedTrackId: Long,
        expectedSize: Int
    ): Boolean {
        val resolved = resolve(requireDelete = false, requireStatePosition = true)
        val state = resolved.statePosition
            ?: error("GMMP current-position writer unavailable")
        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("Track Mix Current queue entry unavailable")
        currentQueueEntryIdHint?.let { expected ->
            require(current.queueEntryId == expected) {
                "Track Mix pinned Current queue entry changed after native refill"
            }
        }
        require(current.track.id == selectedTrackId) {
            "Track Mix Current changed after native refill"
        }
        val entries = resolved.context.items.map { item ->
            TrackMixPlan.NativeQueueEntry(
                queueId = item.queueEntryId,
                trackId = item.track.id,
                position = item.queuePosition
            )
        }
        val plan = TrackMixZeroPoolPolicy.rebasePlan(
            entries = entries,
            currentEntryId = current.queueEntryId,
            expectedSize = expectedSize
        )
        val rowsById = resolved.rows.associateBy {
            number(resolved.queueId, it).toLong()
        }
        val oldPositions = entries.associate { it.queueId to it.position }
        val changed = arrayListOf<Any>()
        plan.positionsByEntryId.forEach { (queueId, position) ->
            val row = rowsById[queueId]
                ?: error("Native refill row disappeared before rebase")
            if (number(resolved.position, row).toInt() != position) {
                setInt(resolved.position, row, position)
                changed += row
            }
        }
        val oldState = state.read()
        try {
            if (changed.isNotEmpty()) {
                resolved.update.invoke(resolved.dao, ArrayList(changed))
            }
            if (oldState != plan.currentPosition) {
                state.write(plan.currentPosition)
            }

            val verified = GmmpQueueReader().read(
                autoDj,
                state.read(),
                current.queueEntryId
            ) ?: error("Zero-pool native refill rebase verification unavailable")
            val ordered = verified.items.sortedBy { it.queuePosition }
            check(ordered.size == expectedSize) {
                "Zero-pool native refill size changed during rebase"
            }
            check(ordered.map { it.queuePosition } == (1..expectedSize).toList()) {
                "Zero-pool native refill positions are not compact"
            }
            val verifiedCurrent = verified.items.singleOrNull {
                it.state == QueueItemState.CURRENT
            }
            check(
                verified.currentQueuePosition == 1 &&
                    verifiedCurrent?.queueEntryId == current.queueEntryId &&
                    verifiedCurrent.track.id == selectedTrackId &&
                    verifiedCurrent.queuePosition == 1
            ) {
                "Zero-pool Current changed during refill rebase"
            }
        } catch (failure: Throwable) {
            runCatching {
                val rollback = arrayListOf<Any>()
                oldPositions.forEach { (queueId, position) ->
                    rowsById[queueId]?.let { row ->
                        setInt(resolved.position, row, position)
                        rollback += row
                    }
                }
                if (rollback.isNotEmpty()) {
                    resolved.update.invoke(resolved.dao, rollback)
                }
                if (state.read() != oldState) state.write(oldState)
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        Log.i(
            TAG,
            "TRACK MIX ZERO-POOL REBASE | verified=true" +
                " | size=" + expectedSize +
                " | changed=" + changed.size +
                " | currentPosition=1"
        )
        return true
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


    private fun resolve(
        requireDelete: Boolean,
        requireStatePosition: Boolean
    ): Resolved {
        val absoluteHint = verifiedPositionWriter?.read()
        val context = GmmpQueueReader().read(
            autoDj,
            absoluteHint,
            currentQueueEntryIdHint
        )
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

    private fun setLong(field: Field, target: Any, value: Long) {
        when (field.type) {
            java.lang.Long.TYPE -> field.setLong(target, value)
            java.lang.Long::class.java -> field.set(target, value)
            Integer.TYPE, Integer::class.java -> {
                require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                if (field.type == Integer.TYPE) field.setInt(target, value.toInt())
                else field.set(target, value.toInt())
            }
            else -> error("GMMP queue Track-ID field is not numeric")
        }
    }

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
