package io.github.alagga.gonesmart

import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Scans the GMMP-native parsed Smart-rule graph read-only, without relying on
 * renamed R8 leaf/group class names. GMMP 4.2.1's APK parses native XML
 * Rules via dw3.f -> ct4 leaf and gt4 group, both extending dt4. Do not
 * traverse android's java.lang.Object runtime shadow fields: Class objects
 * are not GMMP rule nodes. Any *actual* unreadable or unbounded rule branch
 * still blocks physical .spl relocation.
 */
internal object SmartNativeRuleGraphInspector {
    enum class Result { CLEAR, REFERENCED, UNKNOWN }

    fun inspect(
        roots: Iterable<*>,
        selectedCanonicalPaths: Set<String>,
        nodeLimit: Int = 1_024
    ): Result {
        if (nodeLimit <= 0) return Result.UNKNOWN
        val visited = IdentityHashMap<Any, Boolean>()
        var nodes = 0

        fun scan(value: Any?, depth: Int): Result {
            if (value == null) return Result.CLEAR
            if (++nodes > nodeLimit || depth > 20) return Result.UNKNOWN
            if (value is String) {
                val raw = value.substringBefore('|').trim()
                if (!raw.endsWith(".spl", ignoreCase = true)) return Result.CLEAR
                val path = runCatching { File(raw).canonicalPath }.getOrNull()
                    ?: return Result.UNKNOWN
                return if (path in selectedCanonicalPaths) Result.REFERENCED
                    else Result.CLEAR
            }
            if (value is File) {
                val path = runCatching { value.canonicalPath }.getOrNull()
                    ?: return Result.UNKNOWN
                return if (path in selectedCanonicalPaths) Result.REFERENCED
                    else Result.CLEAR
            }
            if (value is Number || value is Boolean || value is Char ||
                value is Enum<*>
            ) return Result.CLEAR
            if (visited.put(value, true) != null) return Result.CLEAR

            val children: List<Any?> = when (value) {
                is Iterable<*> -> value.take(nodeLimit + 1).toList()
                is Map<*, *> -> value.entries.take(nodeLimit / 2 + 1)
                    .flatMap { listOf(it.key, it.value) }
                is Array<*> -> if (value.size <= nodeLimit) value.toList()
                    else return Result.UNKNOWN
                else -> {
                    val type = value.javaClass
                    if (type.isArray || type.name.startsWith("java.") ||
                        type.name.startsWith("android.")
                    ) return Result.UNKNOWN
                    val fields = generateSequence<Class<*>>(type) {
                        it.superclass
                    }.takeWhile { it != Any::class.java }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter {
                            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
                        }.take(65).toList()
                    // Never silently omit field 65 or certify an unknown,
                    // opaque rule node that contains no inspectable state.
                    if (fields.isEmpty() || fields.size > 64) {
                        return Result.UNKNOWN
                    }
                    val reads = fields.map { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(value)
                        }
                    }
                    if (reads.any { it.isFailure }) return Result.UNKNOWN
                    reads.map { it.getOrNull() }
                }
            }
            var uncertain = false
            for (child in children) {
                when (scan(child, depth + 1)) {
                    Result.REFERENCED -> return Result.REFERENCED
                    Result.UNKNOWN -> uncertain = true
                    Result.CLEAR -> Unit
                }
                if (uncertain) return Result.UNKNOWN
            }
            return Result.CLEAR
        }
        return scan(roots, 0)
    }
}
