package io.github.alagga.gonesmart

import java.lang.reflect.Modifier

/**
 * Failure-only, read-only diagnostics for generated Room adapters owned
 * directly by the runtime Queue DAO.
 *
 * Only nested adapter objects owned by the concrete DAO that expose the
 * generated two-argument void binder shape are inspected. Their no-arg String
 * methods are Room SQL-description boundaries; no DAO query, reactive source,
 * SQLite statement, binder or writer is invoked here.
 */
internal object NativeQueueRoomAdapterDiagnostics {
    fun describe(dao: Any): String {
        val daoType = dao.javaClass
        val adapters = hierarchyFields(daoType).mapNotNull { field ->
            if (field.declaringClass != daoType) return@mapNotNull null
            field.isAccessible = true
            val value = runCatching { field.get(dao) }.getOrNull()
                ?: return@mapNotNull null
            val type = value.javaClass
            val nestedOwner = type.enclosingClass
            if (nestedOwner != daoType &&
                !type.name.startsWith(daoType.name + "$")
            ) return@mapNotNull null

            val methods = generateSequence<Class<*>>(type) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) }
                .distinctBy {
                    it.declaringClass.name + "|" + it.name + "|" +
                        it.parameterTypes.joinToString(",") { p -> p.name } +
                        "|" + it.returnType.name
                }
                .toList()

            // A direct nested field alone is not enough: unrelated helper or
            // database-state objects may also be nested. Generated Room
            // adapters expose the binder boundary (statement, entity)->void.
            // Require that metadata-only shape BEFORE invoking any String
            // method on the object.
            val binderMethods = methods.filter {
                it.parameterCount == 2 &&
                    it.returnType == java.lang.Void.TYPE
            }
            if (binderMethods.isEmpty()) return@mapNotNull null

            val sql = methods.filter {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.declaringClass != Any::class.java
            }.mapNotNull { method ->
                runCatching {
                    method.isAccessible = true
                    val raw = method.invoke(value) as? String
                        ?: return@runCatching null
                    method.name + "=" + sanitizeSql(raw)
                }.getOrNull()
            }.ifEmpty { listOf("none") }

            val binders = binderMethods.take(8).joinToString(",") { method ->
                method.name + "(" +
                    method.parameterTypes.joinToString(",") { it.name } +
                    ")" +
                    if (method.isSynthetic || method.isBridge) "[bridge]" else ""
            }

            val genericSuper = runCatching {
                type.genericSuperclass?.typeName ?: "none"
            }.getOrDefault("none")
            val genericInterfaces = runCatching {
                type.genericInterfaces.joinToString(",") { it.typeName }
            }.getOrDefault("none").ifBlank { "none" }

            field.name + "->" + type.name +
                "{sql=" + sql.joinToString(";") +
                ";genericSuper=" + genericSuper +
                ";genericInterfaces=" + genericInterfaces +
                ";bind=" + binders + "}"
        }
        return adapters.joinToString(";").ifBlank { "none" }
    }

    private fun sanitizeSql(sql: String): String {
        val compact = sql.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= 240) compact
        else compact.take(237) + "..."
    }

    private fun hierarchyFields(type: Class<*>) =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) && !it.isSynthetic
            }
            .toList()
}
