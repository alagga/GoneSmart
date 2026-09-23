package io.github.alagga.gonesmart

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One lightweight, read-only check per companion-app start.
 *
 * GitHub's /releases/latest endpoint returns the latest PUBLISHED stable
 * release. This check deliberately never downloads or installs an APK:
 * Obtainium is the user's update manager.
 */
internal object GitHubReleaseChecker {

    private const val RELEASE_API =
        "https://api.github.com/repos/alagga/GoneSmart/releases/latest"

    sealed interface State {
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class NewVersion(val version: String) : State
        data class DevelopmentBuild(val version: String) : State
        data class Unavailable(val reason: String) : State
    }

    fun check(installedVersion: String, onResult: (State) -> Unit) {
        Thread({
            val state: State = try {
                fetchLatest(installedVersion)
            } catch (_: java.net.SocketTimeoutException) {
                State.Unavailable("GitHub timed out.")
            } catch (_: java.io.IOException) {
                State.Unavailable("GitHub could not be reached.")
            } catch (_: SecurityException) {
                State.Unavailable("Network access is unavailable.")
            } catch (_: Exception) {
                State.Unavailable("Invalid GitHub response.")
            }
            onResult(state)
        }, "GoneSmart-release-check").start()
    }

    private fun fetchLatest(installedVersion: String): State {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        connection.setRequestProperty(
            "Accept",
            "application/vnd.github+json"
        )
        connection.setRequestProperty(
            "User-Agent",
            "GoneSmart-Android/$installedVersion"
        )
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return State.Unavailable(
                    if (code == 403 || code == 429) {
                        "GitHub rate limit reached; try again later."
                    } else {
                        "GitHub returned HTTP $code."
                    }
                )
            }
            val json = connection.inputStream.bufferedReader()
                .use { reader -> JSONObject(reader.readText()) }
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty() || json.optBoolean("draft") ||
                json.optBoolean("prerelease")
            ) {
                return State.Unavailable("No stable release is available.")
            }
            val latestVersion = tag.removePrefix("v")
            return when (compareVersions(installedVersion, latestVersion)) {
                null -> State.Unavailable(
                    "Could not compare installed and published versions."
                )
                -1 -> State.NewVersion(latestVersion)
                0 -> State.UpToDate(latestVersion)
                else -> State.DevelopmentBuild(latestVersion)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Comparison is intentionally based on versionName, never versionCode:
     * Obtainium follows the published GitHub Release tag. An installed
     * alpha/beta counts as older than the stable release of the same
     * numeric version. Returns null for non-semver tags rather than
     * incorrectly announcing a downgrade.
     */
    internal fun compareVersions(installed: String, latest: String): Int? {
        val current = parseVersion(installed) ?: return null
        val published = parseVersion(latest) ?: return null
        for (index in 0..2) {
            val order = current.numbers[index]
                .compareTo(published.numbers[index])
            if (order != 0) return order.coerceIn(-1, 1)
        }
        val currentPre = current.prerelease
        val publishedPre = published.prerelease
        if (currentPre == null && publishedPre != null) return 1
        if (currentPre != null && publishedPre == null) return -1
        if (currentPre == null) return 0
        return currentPre.compareTo(publishedPre!!).coerceIn(-1, 1)
    }

    private data class Version(
        val numbers: List<Long>,
        val prerelease: String?
    )

    private val versionPattern = Regex(
        "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.-]+))?(?:\\+[A-Za-z0-9.-]+)?$"
    )

    private fun parseVersion(input: String): Version? {
        val match = versionPattern.matchEntire(input.trim()) ?: return null
        val numbers = (1..3).map {
            match.groupValues[it].toLongOrNull() ?: return null
        }
        val prerelease = match.groupValues[4].ifBlank { null }
        return Version(numbers, prerelease)
    }
}
