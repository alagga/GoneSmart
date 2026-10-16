package io.github.alagga.gonesmart

/**
 * Process-local marker for synchronous GMMP SharedPreferences writes initiated
 * by GoneSmart itself. The passive preference observer uses this only to avoid
 * mistaking a GoneSmart preset-label write for a natural GMMP preset action.
 */
object AudioProfileMutationScope {
    private val depth = ThreadLocal<Int>()

    fun isActive(): Boolean = (depth.get() ?: 0) > 0

    fun <T> run(block: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        return try {
            block()
        } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
