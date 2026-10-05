package io.github.alagga.gonesmart

internal object StatusHealthPolicy {
    enum class Tone {
        GREEN,
        AMBER,
        RED
    }

    // module.prop targets libxposed API 102 and requires at least API 101.
    const val MIN_XPOSED_API = 101
    const val TARGET_XPOSED_API = 102

    fun gmmp(installed: Boolean, running: Boolean): Tone = when {
        !installed -> Tone.RED
        !running -> Tone.AMBER
        else -> Tone.GREEN
    }

    fun framework(serviceAvailable: Boolean, apiVersion: Int?): Tone = when {
        !serviceAvailable -> Tone.RED
        apiVersion == null || apiVersion !in MIN_XPOSED_API..TARGET_XPOSED_API ->
            Tone.AMBER
        else -> Tone.GREEN
    }

    fun runtime(
        serviceAvailable: Boolean,
        gmmpInstalled: Boolean,
        running: Boolean,
        enabled: Boolean,
        runtimeMode: String
    ): Tone = when {
        !serviceAvailable || !gmmpInstalled -> Tone.RED
        runtimeMode == GoneSmartRuntimeContract.MODE_STOPPED -> Tone.RED
        !running || !enabled -> Tone.AMBER
        runtimeMode == GoneSmartRuntimeContract.MODE_FALLBACK -> Tone.AMBER
        runtimeMode == GoneSmartRuntimeContract.MODE_SMART -> Tone.GREEN
        else -> Tone.AMBER
    }

    fun compatibility(state: GmmpCompatibilityPolicy.State): Tone = when (state) {
        GmmpCompatibilityPolicy.State.TESTED -> Tone.GREEN
        GmmpCompatibilityPolicy.State.UNTESTED -> Tone.AMBER
        GmmpCompatibilityPolicy.State.UNKNOWN -> Tone.RED
    }

    /** Overall Status card health: the most severe child state wins. */
    fun overall(vararg tones: Tone): Tone = when {
        tones.any { it == Tone.RED } -> Tone.RED
        tones.any { it == Tone.AMBER } -> Tone.AMBER
        else -> Tone.GREEN
    }
}
