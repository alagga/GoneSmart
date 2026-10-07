package io.github.alagga.gonesmart

internal object GmmpUpdateWarningPolicy {
    fun shouldShow(
        installedVersion: String?,
        acknowledgedVersions: Set<String>
    ): Boolean {
        val version = installedVersion?.trim().orEmpty()
        return version.isNotEmpty() && version !in acknowledgedVersions
    }

    fun withAcknowledged(
        installedVersion: String,
        acknowledgedVersions: Set<String>
    ): Set<String> {
        val version = installedVersion.trim()
        if (version.isEmpty()) return acknowledgedVersions
        return acknowledgedVersions + version
    }
}
