package io.github.alagga.gonesmart

internal object TrackMixCurrentPathRebindPolicy {
    data class Resolution(
        val index: Int?,
        val matchCount: Int
    )

    fun resolve(
        queuePaths: List<String?>,
        expectedPath: String?
    ): Resolution {
        if (expectedPath.isNullOrBlank()) return Resolution(null, 0)
        val matches = queuePaths.indices.filter { index ->
            queuePaths[index] == expectedPath
        }
        return Resolution(
            index = matches.singleOrNull(),
            matchCount = matches.size
        )
    }
}
