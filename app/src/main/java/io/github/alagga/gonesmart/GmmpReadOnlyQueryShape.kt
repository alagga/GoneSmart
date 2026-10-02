package io.github.alagga.gonesmart

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pure reflection policy for Room's read-only query carrier.
 * Kept Android-free so GMMP remaps can be regression-tested on the JVM.
 */
internal object GmmpReadOnlyQueryShape {
    fun legacyConstructor(type: Class<*>): Constructor<*>? =
        type.declaredConstructors.filter {
            val p = it.parameterTypes
            p.size == 2 &&
                p[0] == String::class.java &&
                p[1].isArray &&
                !p[1].componentType.isPrimitive
        }.singleOrNull()

    fun pooledFactory(type: Class<*>): Method? {
        val candidates = type.declaredMethods.filter { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) &&
                type.isAssignableFrom(method.returnType) &&
                p.size == 2 &&
                p[0] == String::class.java &&
                p[1] == Integer.TYPE
        }
        require(candidates.size <= 1) {
            "Room pooled query factory is structurally ambiguous"
        }
        return candidates.singleOrNull()
    }

    /**
     * GMMP 4.2.1/r15 proves p94 is a concrete Room query carrier with only
     * an (int) constructor and no static factory after R8. The constructor is
     * safe only when SQL ownership can also be established by an instance
     * initializer or the strict Room field layout below.
     */
    fun capacityConstructor(type: Class<*>): Constructor<*>? =
        type.declaredConstructors.filter {
            it.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
        }.singleOrNull()

    fun directInitializer(type: Class<*>): Method? {
        val candidates =
            generateSequence<Class<*>>(type) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { method ->
            val p = method.parameterTypes
            !Modifier.isStatic(method.modifiers) &&
                !Modifier.isAbstract(method.modifiers) &&
                method.returnType == java.lang.Void.TYPE &&
                p.size == 2 &&
                p[0] == String::class.java &&
                p[1] == Integer.TYPE
        }.distinctBy {
            it.name + "|" +
                it.parameterTypes.joinToString(",") { p -> p.name } +
                "|" + it.returnType.name
        }.toList()
        require(candidates.size <= 1) {
            "Room direct query initializer is structurally ambiguous"
        }
        return candidates.singleOrNull()
    }

    /**
     * Field fallback for an R8 build that inlined RoomSQLiteQuery.init().
     * A single mutable String SQL field plus at least two mutable int fields
     * (capacity + argCount) is the minimum safe shape. The runtime resolver
     * still verifies their constructor-produced values before writing them.
     */
    fun directFieldLayout(type: Class<*>): Pair<Field, List<Field>>? {
        val fields = generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isFinal(it.modifiers) &&
                    !it.isSynthetic
            }
            .toList()
        val sql = fields.filter { it.type == String::class.java }
            .singleOrNull() ?: return null
        val ints = fields.filter { it.type == Integer.TYPE }
        if (ints.size < 2) return null
        return sql to ints
    }

    fun directArgumentCountField(
        query: Any,
        capacity: Int
    ): Field? {
        val (_, ints) = directFieldLayout(query.javaClass) ?: return null
        val values = ints.mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                field to field.getInt(query)
            }.getOrNull()
        }
        if (values.size != ints.size) return null
        val zero = values.filter { it.second == 0 }.map { it.first }
        val capacityFields =
            values.filter { it.second == capacity }.map { it.first }
        if (zero.size != 1 || capacityFields.isEmpty()) return null
        return zero.single()
    }

    fun newDirectCarrier(
        type: Class<*>,
        sql: String,
        argumentCount: Int
    ): Any? {
        val constructor = capacityConstructor(type) ?: return null
        constructor.isAccessible = true
        val capacity = maxOf(1, argumentCount)
        val query = constructor.newInstance(capacity) ?: return null

        val initializer = directInitializer(type)
        if (initializer != null) {
            initializer.isAccessible = true
            initializer.invoke(query, sql, argumentCount)
            return query
        }

        val (sqlField, _) = directFieldLayout(type) ?: return null
        sqlField.isAccessible = true
        sqlField.set(query, sql)
        val argCount = directArgumentCountField(query, capacity)
            ?: return null
        argCount.isAccessible = true
        argCount.setInt(query, argumentCount)
        return query
    }

    fun directCarrierSupported(type: Class<*>): Boolean =
        capacityConstructor(type) != null &&
            (
                directInitializer(type) != null ||
                    directFieldLayout(type) != null
            )

    fun supported(type: Class<*>): Boolean =
        type.isInterface ||
            legacyConstructor(type) != null ||
            pooledFactory(type) != null ||
            directCarrierSupported(type)
}
