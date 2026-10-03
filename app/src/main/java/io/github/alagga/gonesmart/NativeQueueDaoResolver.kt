package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.Executor

/**
 * Resolves the native DAO that actually owns queue_table mutation adapters.
 *
 * The Auto-DJ object's historical `q` field is not semantic identity: the
 * GMMP 4.2.1 r31 device log proves its runtime `d85` object's direct generated
 * CRUD adapters write `tracks`, not `queue_table`. This resolver therefore
 * treats immediate Auto-DJ objects and already-initialized objects cached by
 * the verified GMDatabase instance only as candidates.
 *
 * A candidate becomes the Queue writer DAO only when
 * [NativeQueueEntityAdapterTypeResolver] proves a generated Room adapter whose
 * own SQL names queue_table and yields one unambiguous native entity class.
 * Discovery invokes no DAO accessor/query/reactive method and no writer.
 */
internal object NativeQueueDaoResolver {
    data class Result(
        val dao: Any,
        val entity: NativeQueueEntityAdapterTypeResolver.Result,
        val evidence: String
    )

    private data class Candidate(
        val label: String,
        val value: Any
    )

    fun resolve(autoDj: Any): Result? =
        resolveCandidates(
            collectCandidates(autoDj).map { it.label to it.value }
        )

    /** Test seam for ownership resolution without an Android Room runtime. */
    internal fun resolveCandidates(
        candidates: List<Pair<String, Any>>
    ): Result? {
        val unique = arrayListOf<Pair<String, Any>>()
        candidates.forEach { candidate ->
            if (unique.none { it.second === candidate.second }) {
                unique += candidate
            }
        }

        val resolved = unique.mapNotNull { (label, value) ->
            val entity = NativeQueueEntityAdapterTypeResolver.resolve(value)
                ?: return@mapNotNull null
            Result(
                dao = value,
                entity = entity,
                evidence = label + "->" + entity.evidence
            )
        }
        return resolved.singleOrNull()
    }

    /**
     * Failure-only metadata. It never invokes a database DAO accessor; null
     * cache fields and no-arg custom-return accessors are described by type so
     * a later revision can target one exact missing lazy boundary if needed.
     */
    fun diagnosticShape(autoDj: Any): String {
        val candidates = collectCandidates(autoDj)
        val candidateAdapters = candidates.mapNotNull { candidate ->
            val shape = NativeQueueRoomAdapterDiagnostics.describe(
                candidate.value
            )
            if (shape == "none") null
            else candidate.label + "->" +
                candidate.value.javaClass.name + "{" + shape + "}"
        }.take(12).joinToString(";").ifBlank { "none" }

        val database = findDatabase(autoDj)
        val databaseShape = if (database == null) {
            "unresolved"
        } else {
            val fields = hierarchyFields(database.javaClass)
                .take(40)
                .joinToString(",") { field ->
                    field.isAccessible = true
                    val runtime = runCatching { field.get(database) }
                        .getOrNull()
                    field.declaringClass.name + "." + field.name +
                        ":" + field.type.name + "->" +
                        (runtime?.javaClass?.name ?: "null")
                }
                .ifBlank { "none" }
            val accessors = GmmpReflectionPolicy
                .callableMethods(database.javaClass)
                .filter {
                    !Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 &&
                        isCustomReferenceType(it.returnType)
                }
                .take(40)
                .joinToString(",") {
                    it.declaringClass.name + "." + it.name +
                        "():" + it.returnType.name
                }
                .ifBlank { "none" }
            database.javaClass.name +
                "{fields=" + fields + ";accessors=" + accessors + "}"
        }

        return bound(
            "candidates=" + candidateAdapters +
                " | database=" + databaseShape
        )
    }

    private fun collectCandidates(autoDj: Any): List<Candidate> {
        val result = arrayListOf<Candidate>()
        hierarchyFields(autoDj.javaClass).forEach { field ->
            field.isAccessible = true
            val value = runCatching { field.get(autoDj) }.getOrNull()
                ?: return@forEach
            if (isCandidateObject(value)) {
                result += Candidate(
                    "autoDj:" + field.declaringClass.name + "." + field.name,
                    value
                )
            }
        }

        findDatabase(autoDj)?.let { database ->
            hierarchyFields(database.javaClass).forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(database) }.getOrNull()
                    ?: return@forEach
                if (isCandidateObject(value)) {
                    result += Candidate(
                        "database:" + field.declaringClass.name +
                            "." + field.name,
                        value
                    )
                }
            }
        }

        val unique = arrayListOf<Candidate>()
        result.forEach { candidate ->
            if (unique.none { it.value === candidate.value }) {
                unique += candidate
            }
        }
        return unique
    }

    private fun findDatabase(autoDj: Any): Any? {
        val candidates = hierarchyFields(autoDj.javaClass).mapNotNull { field ->
            field.isAccessible = true
            val value = runCatching { field.get(autoDj) }.getOrNull()
                ?: return@mapNotNull null
            if (isDatabaseLike(field, value)) value else null
        }
        val unique = arrayListOf<Any>()
        candidates.forEach { candidate ->
            if (unique.none { it === candidate }) unique += candidate
        }
        return unique.singleOrNull()
    }

    private fun isDatabaseLike(field: Field, value: Any): Boolean {
        if (field.type.name ==
            "gonemad.gmmp.data.database.GMDatabase"
        ) return true
        return generateSequence<Class<*>>(value.javaClass) { it.superclass }
            .any {
                it.name == "androidx.room.RoomDatabase" ||
                    it.name == "gonemad.gmmp.data.database.GMDatabase"
            }
    }

    private fun isCandidateObject(value: Any): Boolean {
        if (isDatabaseRuntime(value.javaClass)) return false
        if (value is Collection<*> ||
            value is Map<*, *> ||
            value is Executor ||
            value is String ||
            value is Number ||
            value is Boolean ||
            value is Enum<*> ||
            value is Class<*>
        ) return false
        val name = value.javaClass.name
        return !name.startsWith("java.") &&
            !name.startsWith("android.") &&
            !name.startsWith("kotlin.")
    }

    private fun isCustomReferenceType(type: Class<*>): Boolean {
        if (type == java.lang.Void.TYPE ||
            type.isPrimitive ||
            type == Any::class.java ||
            type == String::class.java
        ) return false
        val name = type.name
        return !name.startsWith("java.") &&
            !name.startsWith("android.") &&
            !name.startsWith("kotlin.")
    }

    private fun isDatabaseRuntime(type: Class<*>): Boolean =
        generateSequence<Class<*>>(type) { it.superclass }.any {
            it.name == "androidx.room.RoomDatabase" ||
                it.name == "gonemad.gmmp.data.database.GMDatabase"
        }

    private fun hierarchyFields(type: Class<*>): List<Field> =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .distinctBy {
                it.declaringClass.name + "|" + it.name + "|" + it.type.name
            }
            .toList()

    private fun bound(value: String): String =
        if (value.length <= 5000) value else value.take(4997) + "..."
}
