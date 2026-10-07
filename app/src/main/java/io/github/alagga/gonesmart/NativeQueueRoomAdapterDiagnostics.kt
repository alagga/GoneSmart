package io.github.alagga.gonesmart

import java.lang.reflect.Modifier

/**
 * Failure-only, read-only diagnostics for generated Room adapters owned by a
 * runtime DAO and its generated DAO superclass hierarchy.
 *
 * Only nested adapter objects owned by the class that declares the field and
 * exposing the generated two-argument erased binder shape are inspected.
 * Their no-arg String methods are Room SQL-description boundaries; no DAO
 * query, reactive source, SQLite statement, binder or writer is invoked here.
 */
internal object NativeQueueRoomAdapterDiagnostics {
    fun describe(dao: Any): String {
        val adapters = hierarchyFields(dao.javaClass).mapNotNull { field ->
            field.isAccessible = true
            val value = runCatching { field.get(dao) }.getOrNull()
                ?: return@mapNotNull null
            val type = value.javaClass
            val owner = field.declaringClass
            val nestedOwner = type.enclosingClass
            if (nestedOwner != owner &&
                !type.name.startsWith(owner.name + "$")
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

            // A nested field alone is not enough: unrelated helper or
            // database-state objects may also be nested. Generated Room
            // adapters expose an erased binder (statement,Object)->void.
            // Require that metadata-only shape BEFORE invoking any String
            // method on the object.
            val binderMethods = methods.filter {
                it.declaringClass != Any::class.java &&
                    it.parameterCount == 2 &&
                    !it.parameterTypes[0].isPrimitive &&
                    it.parameterTypes[1] == Any::class.java &&
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

            owner.name + "." + field.name + "->" + type.name +
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
