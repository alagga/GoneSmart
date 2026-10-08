package io.github.alagga.gonesmart

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PlaylistBridgeNativeSentinelPolicyTest {
    @Test
    fun falseSentinelUsesNativeIdEquality() {
        assertArrayEquals(
            arrayOf<Any?>(100, 0, Long.MIN_VALUE.toString(), 0),
            PlaylistBridgeNativeSentinelPolicy.ruleArguments(false)
        )
    }

    @Test
    fun trueSentinelUsesNativeIdInequality() {
        assertArrayEquals(
            arrayOf<Any?>(100, 1, Long.MIN_VALUE.toString(), 0),
            PlaylistBridgeNativeSentinelPolicy.ruleArguments(true)
        )
    }
}
