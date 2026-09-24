package io.github.alagga.gonesmart

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Read-only inspection of GMMP's complete native playlist adapter data.
 * The known zn3.i0()/t23.r() surface contains headers rather than the
 * full xn3 playlist dataset. Explore native fields and collections only;
 * never invoke obfuscated methods with unknown side effects.
 */
internal object NativePlaylistSourceInspector {
    data class Result(
        val paths: List<String>,
        val traces: List<String>,
        val visitedObjects: Int,
        val truncated: Boolean,
        val models: List<NativePlaylistTitleResolver.Model> = emptyList()
    )

    private data class Entry(val value: Any, val via: String, val depth: Int)
    private const val MAX_VISITED = 8000
    private const val MAX_DEPTH = 7
    private const val MAX_TRACES = 55
    private const val MAX_CONTAINER_ITEMS = 4000

    fun inspect(adapter: Any, expectedRows: Int = -1): Result {
        val paths = linkedSetOf<String>()
        val models = linkedMapOf<String, NativePlaylistTitleResolver.Model>()
        val traces = linkedSetOf<String>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val queue = java.util.ArrayDeque<Entry>()
        queue.add(Entry(adapter, "adapter", 0))
        var truncated = false

        fun log(value: String) {
            if (traces.size < MAX_TRACES) traces.add(value)
        }

        fun append(value: Any?, via: String, depth: Int) {
            if (value == null || depth > MAX_DEPTH ||
                value is String || value is CharSequence ||
                value is Number || value is Boolean || value is Enum<*>
            ) return
            queue.add(Entry(value, via, depth))
        }

        while (queue.isNotEmpty()) {
            if (visited.size >= MAX_VISITED) {
                truncated = true
                break
            }
            val (value, via, depth) = queue.removeFirst()
            if (!visited.add(value)) continue
            val type = value.javaClass
            if (type.simpleName == "xn3") {
                val path = runCatching {
                    findField(type, "q")?.get(value) as? String
                }.getOrNull()
                if (!path.isNullOrBlank()) {
                    paths.add(path)
                    if (!models.containsKey(path)) {
                        models[path] = NativePlaylistTitleResolver.Model(
                            path, textFields(value)
                        )
                    }
                }
                continue
            }

            when (value) {
                is Collection<*> -> {
                    if (depth <= 3 || value.size > 1) {
                        log(via + ":collection(" + type.simpleName +
                            ",size=" + value.size +
                            ",first=" + (value.firstOrNull()?.javaClass?.name ?: "null") + ")")
                    }
                    var count = 0
                    for (member in value) {
                        if (count++ >= MAX_CONTAINER_ITEMS) {
                            truncated = true
                            break
                        }
                        append(member, via + "[" + count + "]", depth + 1)
                    }
                    continue
                }
                is Map<*, *> -> {
                    log(via + ":map(" + type.simpleName + ",size=" + value.size + ")")
                    var count = 0
                    for (member in value.values) {
                        if (count++ >= MAX_CONTAINER_ITEMS) {
                            truncated = true
                            break
                        }
                        append(member, via + "[" + count + "]", depth + 1)
                    }
                    continue
                }
            }

            if (type.isArray) {
                val size = ReflectArray.getLength(value)
                log(via + ":array(" + type.componentType?.simpleName +
                    ",size=" + size + ")")
                for (index in 0 until minOf(size, MAX_CONTAINER_ITEMS)) {
                    append(ReflectArray.get(value, index), via + "[" + index + "]", depth + 1)
                }
                if (size > MAX_CONTAINER_ITEMS) truncated = true
                continue
            }

            if (!isNativeCarrier(type) && depth > 0) continue
            var cursor: Class<*>? = type
            while (cursor != null && isNativeCarrier(cursor)) {
                for (field in cursor.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                        field.isSynthetic
                    ) continue
                    val member = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull() ?: continue
                    val memberType = member.javaClass
                    val size = when (member) {
                        is Collection<*> -> member.size
                        is Map<*, *> -> member.size
                        else -> if (memberType.isArray) ReflectArray.getLength(member) else -1
                    }
                    if (depth <= 1 || size >= 2) {
                        log(via + "." + field.name + ":" + memberType.name +
                            if (size >= 0) "[" + size + "]" else "")
                    }
                    if (isTraversable(member)) {
                        append(member, via + "." + field.name, depth + 1)
                    }
                }
                cursor = cursor.superclass
            }
        }

        // Only the standard getItem(int) is invoked: its semantics are
        // read-only. Never invoke unknown one-int GMMP methods.
        if (expectedRows in 1..MAX_CONTAINER_ITEMS && paths.size < expectedRows) {
            val getter = findItemGetter(adapter.javaClass)
            if (getter != null) {
                log("adapter.getItem(int):" + getter.returnType.name)
                for (index in 0 until expectedRows) {
                    val model = runCatching {
                        getter.isAccessible = true
                        getter.invoke(adapter, index)
                    }.getOrNull() ?: continue
                    if (model.javaClass.simpleName != "xn3") continue
                    val path = runCatching {
                        findField(model.javaClass, "q")?.get(model) as? String
                    }.getOrNull()
                    if (!path.isNullOrBlank()) {
                        paths.add(path)
                        if (!models.containsKey(path)) {
                            models[path] = NativePlaylistTitleResolver.Model(
                                path, textFields(model)
                            )
                        }
                    }
                }
            }
        }
        log("RESULT nativePaths=" + paths.size +
            " expectedAdapterRows=" + expectedRows)
        return Result(
            paths.toList(), traces.toList(), visited.size, truncated,
            models.values.toList()
        )
    }


    /** Read only direct and one-level nested text metadata from xn3. */
    private fun textFields(model: Any): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun inspect(target: Any, prefix: String, limit: Int) {
            var owner: Class<*>? = target.javaClass
            var visitedFields = 0
            while (owner != null && owner != Any::class.java &&
                visitedFields < limit && result.size < 48
            ) {
                for (field in owner.declaredFields) {
                    if (visitedFields++ >= limit || result.size >= 48) break
                    if (field.isSynthetic ||
                        java.lang.reflect.Modifier.isStatic(field.modifiers)
                    ) continue
                    val member = runCatching {
                        field.isAccessible = true
                        field.get(target)
                    }.getOrNull() ?: continue
                    val label = prefix + field.name
                    when (member) {
                        is CharSequence -> {
                            val text = member.toString().trim()
                            if (text.isNotBlank() && text.length <= 500) {
                                result[label] = text
                            }
                        }
                        else -> {
                            if (prefix.isEmpty() &&
                                isNativeCarrier(member.javaClass) &&
                                member.javaClass.simpleName != "xn3" &&
                                !member.javaClass.isArray &&
                                member !is Collection<*> &&
                                member !is Map<*, *>
                            ) {
                                inspect(member, label + ".", 12)
                            }
                        }
                    }
                }
                owner = owner.superclass
            }
        }
        inspect(model, "", 48)

        // Only semantically explicit read-only Java/Kotlin getters.
        val getters = setOf("getName", "getTitle", "getDisplayName",
            "getPlaylistName")
        for (method in model.javaClass.methods) {
            if (method.name !in getters || method.parameterCount != 0 ||
                !CharSequence::class.java.isAssignableFrom(method.returnType)
            ) continue
            val text = runCatching { method.invoke(model) as? CharSequence }
                .getOrNull()?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length <= 500) {
                result[method.name] = text
            }
        }
        return result
    }

    private fun isTraversable(value: Any): Boolean =
        value is Collection<*> || value is Map<*, *> ||
            value.javaClass.isArray || isNativeCarrier(value.javaClass)

    private fun isNativeCarrier(type: Class<*>): Boolean {
        val name = type.name
        if (name.startsWith("java.") || name.startsWith("javax.") ||
            name.startsWith("android.") || name.startsWith("androidx.") ||
            name.startsWith("kotlin.") || name.startsWith("kotlinx.") ||
            name.startsWith("com.google.") || name.startsWith("com.afollestad.")
        ) return false
        return !type.isPrimitive && !type.isEnum
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var cursor: Class<*>? = type
        while (cursor != null && cursor != Any::class.java) {
            val owner = cursor
            val field = runCatching { owner.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            cursor = owner.superclass
        }
        return null
    }

    private fun findItemGetter(type: Class<*>): Method? {
        var cursor: Class<*>? = type
        while (cursor != null && cursor != Any::class.java) {
            val owner = cursor
            val candidate = owner.declaredMethods.firstOrNull {
                it.name == "getItem" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    (it.returnType.simpleName == "xn3" ||
                        it.returnType == Any::class.java)
            }
            if (candidate != null) return candidate
            cursor = owner.superclass
        }
        return null
    }
}
