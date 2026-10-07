package io.github.alagga.gonesmart

internal object GmmpCompatibilityPolicy {
    const val TESTED_VERSION = "4.2.1"

    enum class State {
        UNKNOWN,
        TESTED,
        UNTESTED
    }

    fun state(installedVersion: String?): State =
        state(installedVersion, TESTED_VERSION)

    fun state(
        installedVersion: String?,
        testedVersion: String
    ): State = when {
        installedVersion == null -> State.UNKNOWN
        installedVersion == testedVersion -> State.TESTED
        else -> State.UNTESTED
    }
}
