package io.github.alagga.gonesmart

import java.io.File

/**
 * Target a SINGLE native va4 preference delegate, only on the original
 * playlist-creation thread. Never edit GMMP's persisted playlist_saveLocation.
 */
internal class NativePlaylistDestinationScope {
    data class Outcome<T>(val value: T, val substitutions: Int)

    private data class Target(
        val instance: Any,
        val canonicalRoot: String,
        val destination: String,
        var substitutions: Int = 0
    )

    private val current = ThreadLocal<Target?>()

    fun overrideNativeGetter(owner: Any?, proceed: () -> Any?): Any? {
        val value = proceed()
        val active = current.get() ?: return value
        if (owner !== active.instance || value !is String ||
            value.contains("://")
        ) return value
        val canonical = runCatching { File(value).canonicalPath }.getOrNull()
        if (canonical != active.canonicalRoot) return value
        active.substitutions++
        return active.destination
    }

    fun <T> withDestination(
        instance: Any,
        expectedRoot: String,
        destination: String,
        proceed: () -> T
    ): Outcome<T> {
        val previous = current.get()
        val scope = Target(
            instance = instance,
            canonicalRoot = File(expectedRoot).canonicalPath,
            destination = File(destination).canonicalPath
        )
        current.set(scope)
        try {
            val result = proceed()
            return Outcome(result, scope.substitutions)
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
