package io.github.alagga.gonesmart

/** Pure predicates used by the scoped native first-frame opacity manager. */
internal object FirstFrameOpacityPolicy {
    fun matches(actual: String, expected: String): Boolean = actual == expected
    fun current(actual: Any, expected: Any): Boolean = actual === expected
}
