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
    }

    private data class Resolved(
        val dao: Any,
        val rows: List<Any>,
        val queueId: Field,
        val trackId: Field,
        val position: Field,
        val update: Method,
        val delete: Method?,
        val stateHost: Any,
        val statePosition: Field,
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
        val oldState = number(resolved.statePosition, resolved.stateHost).toInt()

        try {
            newOrder.forEachIndexed { index, row ->
                setInt(resolved.position, row, oldPositions[index])
            }
            resolved.update.invoke(
                resolved.dao,
                ArrayList(newOrder)
            )
            setInt(
                resolved.statePosition,
                resolved.stateHost,
                newCurrentPosition
            )
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
                setInt(
                    resolved.statePosition,
                    resolved.stateHost,
                    oldState
                )
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
        setInt(resolved.statePosition, resolved.stateHost, 1)

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
        val methods = concreteMethods(dao.javaClass)
        val listReaders = methods.filter {
            it.parameterCount == 0 &&
                java.util.List::class.java
                    .isAssignableFrom(it.returnType)
        }
        val listCandidates = listReaders.mapNotNull { method ->
            runCatching {
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (method.invoke(dao) as? List<Any?>)
                    ?.filterNotNull()
            }.getOrNull()
        }.filter {
            it.size == context.items.size &&
                it.isNotEmpty()
        }
        val rows = listCandidates.singleOrNull()
            ?: error(
                "GMMP native queue entity reader is not unique: " +
                    listReaders.joinToString(",") { it.name }
            )
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
        val delete = (
            deleteCandidates.singleOrNull { it.name == "O" }
                ?: deleteCandidates.singleOrNull()
            )?.apply { isAccessible = true }
        if (requireDelete && delete == null) {
            error(
                "GMMP Queue DAO delete is not unique: " +
                    deleteCandidates.joinToString(",") { it.name }
            )
        }

        val stateHost = objectField(autoDj, "p")
            ?: error("GMMP queue state unavailable")
        val stateFields = hierarchyFields(stateHost.javaClass).filter {
            it.type == Integer.TYPE ||
                it.type == Integer::class.java
        }.onEach { it.isAccessible = true }
        val statePosition = stateFields.filter {
            runCatching {
                (it.get(stateHost) as? Number)?.toInt() ==
                    context.currentQueuePosition
            }.getOrDefault(false)
        }.singleOrNull() ?: error(
            "GMMP current-position state field is not unique"
        )

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
                " | state=" + stateHost.javaClass.name + "." +
                statePosition.name
        )
        return Resolved(
            dao,
            rows,
            queueId,
            trackId,
            position,
            update,
            delete,
            stateHost,
            statePosition,
            context
        )
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

    private fun concreteMethods(type: Class<*>): List<Method> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                !Modifier.isAbstract(it.modifiers) && !it.isSynthetic
            }
            .distinctBy {
                it.name + "|" +
                    it.parameterTypes.joinToString(",") { p -> p.name } +
                    "|" + it.returnType.name
            }
            .toList()
}
