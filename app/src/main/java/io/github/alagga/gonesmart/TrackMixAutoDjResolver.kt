package io.github.alagga.gonesmart

import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal class TrackMixAutoDjResolver(
    private val acceptedClassName: String = "qr",
    private val refillMethodName: String = "z",
    private val maxDepth: Int = 5,
    private val maxObjects: Int = 240,
    private val maxCollectionItems: Int = 48
) {
    private data class Node(val value: Any, val depth: Int)
    fun resolve(roots: List<Any?>): Any? {
        if (roots.isEmpty()) return null
        val queue = ArrayDeque<Node>()
        roots.filterNotNull().forEach { queue.add(Node(it, 0)) }
        val visited = IdentityHashMap<Any, Boolean>()
        val candidates = arrayListOf<Any>()
        var inspected = 0
        while (queue.isNotEmpty() && inspected < maxObjects) {
            val node = queue.removeFirst()
            if (visited.put(node.value, true) != null) continue
            inspected++
            if (isAcceptedAutoDj(node.value)) {
                if (candidates.none { it === node.value }) candidates += node.value
                if (candidates.size > 1) return null
            }
            if (node.depth >= maxDepth) continue
            children(node.value).take(maxCollectionItems).forEach { child ->
                if (visited[child] == null && shouldTraverse(child)) queue.add(Node(child, node.depth + 1))
            }
        }
        return candidates.singleOrNull()
    }
    private fun isAcceptedAutoDj(value: Any): Boolean {
        if (value.javaClass.name != acceptedClassName) return false
        return methods(value.javaClass).any { method ->
            !Modifier.isStatic(method.modifiers) && method.name == refillMethodName &&
                method.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
        }
    }
    private fun children(value: Any): Sequence<Any> = sequence {
        when (value) {
            is Iterable<*> -> { value.take(maxCollectionItems).filterNotNull().forEach { yield(it) }; return@sequence }
            is Map<*, *> -> { value.entries.take(maxCollectionItems).forEach { e -> e.key?.let { yield(it) }; e.value?.let { yield(it) } }; return@sequence }
        }
        if (value.javaClass.isArray) {
            val size = java.lang.reflect.Array.getLength(value).coerceAtMost(maxCollectionItems)
            repeat(size) { i -> java.lang.reflect.Array.get(value, i)?.let { yield(it) } }
            return@sequence
        }
        fields(value.javaClass).forEach { field ->
            val child = runCatching { field.isAccessible = true; field.get(value) }.getOrNull() ?: return@forEach
            if (!isScalar(child)) yield(child)
        }
    }
    private fun fields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence<Class<*>>(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.filterNot { Modifier.isStatic(it.modifiers) }.toList()
    private fun methods(type: Class<*>): Sequence<java.lang.reflect.Method> =
        generateSequence<Class<*>>(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredMethods.asSequence() }
    private fun shouldTraverse(value: Any): Boolean {
        if (isScalar(value)) return false
        if (isAcceptedAutoDj(value)) return true
        if (value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) return true
        val name = value.javaClass.name
        return !(name.startsWith("java.lang.") || name.startsWith("java.util.concurrent.") || name.startsWith("kotlin."))
    }
    private fun isScalar(value: Any): Boolean = value is CharSequence || value is Number || value is Boolean || value is Char || value.javaClass.isEnum
}
