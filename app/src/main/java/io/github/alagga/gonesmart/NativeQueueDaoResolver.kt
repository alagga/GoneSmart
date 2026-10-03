package io.github.alagga.gonesmart

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executor

/**
 * Resolves the native DAO that actually owns queue_table mutation adapters.
 *
 * GMMP 4.2.1 stores generated DAO instances behind lazy database accessors.
 * Runtime fields on GMDatabase_Impl are therefore not sufficient ownership
 * evidence: the r32 device log showed only r15 lazy holders while the actual
 * DAO accessors remained visible as no-arg custom-return methods.
 *
 * Discovery is still non-mutating. Besides already-live Auto-DJ/database
 * objects, this resolver may invoke only bounded no-arg methods declared by
 * the verified generated GMDatabase implementation whose return type is an
 * interface/abstract custom reference type. Those methods are Room's DAO
 * accessors: invoking one may instantiate/cache a DAO, but does not execute a
 * DAO query or writer. A returned object becomes the Queue writer DAO only
 * when [NativeQueueEntityAdapterTypeResolver] proves a generated Room adapter
 * whose own SQL names queue_table and yields one unambiguous entity class.
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
     * Test seam for the bounded generated-database accessor policy. Returned
     * pairs are the same candidates consumed by [resolveCandidates].
     */
    internal fun databaseAccessorCandidates(
        database: Any
    ): List<Pair<String, Any>> =
        collectDatabaseAccessorCandidates(database)
            .map { it.label to it.value }

    /**
     * Failure-only metadata. DAO accessor invocation is bounded to the same
     * generated-database accessor policy used by normal resolution. It never
     * invokes a returned DAO query/reactive method or writer.
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
        }.take(20).joinToString(";").ifBlank { "none" }

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
            val accessors = databaseAccessorMethods(database)
                .take(40)
                .joinToString(",") {
                    it.declaringClass.name + "." + it.name +
                        "():" + it.returnType.name
                }
                .ifBlank { "none" }
            database.javaClass.name +
                "{fields=" + fields + ";daoAccessors=" + accessors + "}"
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
            // Keep already-live cached objects as cheap evidence.
            hierarchyFields(database.javaClass).forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(database) }.getOrNull()
                    ?: return@forEach
                if (isCandidateObject(value)) {
                    result += Candidate(
                        "database-field:" + field.declaringClass.name +
                            "." + field.name,
                        value
                    )
                }
            }

            // GMMP 4.2.1's GMDatabase_Impl caches DAOs behind r15 lazy
            // holders. Calling a generated DAO accessor is the native Room
            // way to materialize that DAO and does not execute DAO work.
            result += collectDatabaseAccessorCandidates(database)
        }

        val unique = arrayListOf<Candidate>()
        result.forEach { candidate ->
            if (unique.none { it.value === candidate.value }) {
                unique += candidate
            }
        }
        return unique
    }

    private fun collectDatabaseAccessorCandidates(
        database: Any
    ): List<Candidate> =
        databaseAccessorMethods(database)
            .take(32)
            .mapNotNull { method ->
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(database)
                }.getOrNull() ?: return@mapNotNull null
                if (!isCandidateObject(value)) return@mapNotNull null
                Candidate(
                    label = "database-accessor:" +
                        method.declaringClass.name + "." + method.name +
                        "():" + method.returnType.name,
                    value = value
                )
            }

    /**
     * Generated Room DAO accessors are concrete no-arg methods declared by
     * GMDatabase_Impl and return the abstract/interface DAO contract. Requiring
     * that shape excludes Room lifecycle/open-helper methods and arbitrary
     * inherited object helpers from invocation.
     */
    private fun databaseAccessorMethods(database: Any): List<Method> =
        GmmpReflectionPolicy.callableMethods(database.javaClass)
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.declaringClass == database.javaClass &&
                    it.parameterCount == 0 &&
                    isDaoContractType(it.returnType)
            }
            .distinctBy {
                it.name + "|" + it.returnType.name
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
            !name.startsWith("androidx.") &&
            !name.startsWith("kotlin.")
    }

    private fun isDaoContractType(type: Class<*>): Boolean {
        if (!isCustomReferenceType(type) || isDatabaseRuntime(type)) {
            return false
        }
        return type.isInterface || Modifier.isAbstract(type.modifiers)
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
            !name.startsWith("androidx.") &&
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
        if (value.length <= 7000) value else value.take(6997) + "..."
}
