package io.github.alagga.gonesmart

/**
 * Pure Kotlin summary of the companion app's event log. Keep this
 * independent of Android so category totals can be regression-tested
 * without an instrumented device.
 */
internal object GoneSmartLogSummary {
    data class Summary(
        val total: Int,
        val smartDj: Int,
        val playlists: Int,
        val flip: Int,
        val trackMix: Int,
        val ui: Int,
        val system: Int,
        val other: Int
    )

    fun fromText(logText: String): Summary {
        val lines = logText.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val smart = lines.count { it.contains("[Smart DJ]") }
        val playlists = lines.count { it.contains("[Playlists]") }
        val flip = lines.count { it.contains("[Flip]") }
        val mix = lines.count { it.contains("[Track Mix]") }
        val ui = lines.count { it.contains("[UI]") }
        val system = lines.count { it.contains("[System]") }
        return Summary(
            total = lines.size,
            smartDj = smart,
            playlists = playlists,
            flip = flip,
            trackMix = mix,
            ui = ui,
            system = system,
            other = lines.size - smart - playlists - flip - mix - ui - system
        )
    }
}
