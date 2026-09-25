package io.github.alagga.gonesmart

/**
 * Scoped guard for GMMP's synchronous fo3.invoke -> f2.b(j83) call chain.
 * Never suppress unrelated events, threads or later normal multi-adds.
 */
internal class PlaylistPickerCreateOnlyScope {
    private val depth = ThreadLocal<Int>()

    fun eligible(
        enabled: Boolean,
        closeGuardInstalled: Boolean,
        pickerAttached: Boolean,
        selectedDestinations: Int
    ): Boolean = enabled && closeGuardInstalled && pickerAttached &&
        selectedDestinations == 0

    fun <T> duringCreate(block: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        try {
            return block()
        } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }

    fun shouldSuppressClose(eventClassName: String?): Boolean =
        eventClassName == "j83" && (depth.get() ?: 0) > 0
}
