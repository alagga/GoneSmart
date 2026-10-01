package io.github.alagga.gonesmart

internal object GmmpCompatibilityPolicy {
    enum class State {
        UNKNOWN,
        TESTED,
        UNTESTED
    }

    fun state(
        installedVersion: String?,
        testedVersion: String
    ): State = when {
        installedVersion == null -> State.UNKNOWN
        installedVersion == testedVersion -> State.TESTED
        else -> State.UNTESTED
    }
}
