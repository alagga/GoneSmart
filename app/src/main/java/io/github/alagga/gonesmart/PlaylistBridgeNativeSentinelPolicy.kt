package io.github.alagga.gonesmart

/**
 * Native Smart-rule encoding used for Playlist Link's neutral compatibility
 * rules and fail-closed predicate. These values are part of the already
 * accepted GMMP Smart-rule format; evaluation itself stays owned by GMMP.
 */
internal object PlaylistBridgeNativeSentinelPolicy {
    private const val TRACK_ID_FIELD = 100
    private const val EQUALS_OPERATOR = 0
    private const val NOT_EQUALS_OPERATOR = 1

    fun ruleArguments(shouldMatch: Boolean): Array<Any?> =
        arrayOf(
            TRACK_ID_FIELD,
            if (shouldMatch) NOT_EQUALS_OPERATOR else EQUALS_OPERATOR,
            Long.MIN_VALUE.toString(),
            0
        )

    fun trackIdEqualsArguments(trackId: Long): Array<Any?> =
        arrayOf(
            TRACK_ID_FIELD,
            EQUALS_OPERATOR,
            trackId.toString(),
            0
        )
}
