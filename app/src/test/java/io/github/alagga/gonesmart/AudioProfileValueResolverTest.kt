package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioProfileValueResolverTest {

    @Test
    fun primaryValueWinsWhenValid() {
        assertEquals(
            "25",
            AudioProfileValueResolver.resolve("balance.percent", "25", "-40")
        )
    }

    @Test
    fun runtimeFallbackRepairsMissingOrInvalidFrameworkValue() {
        assertEquals(
            "-40",
            AudioProfileValueResolver.resolve("balance.percent", null, "-40")
        )
        assertEquals(
            "-40",
            AudioProfileValueResolver.resolve("balance.percent", "not-a-number", "-40")
        )
    }

    @Test
    fun unknownFieldFailsClosed() {
        assertNull(AudioProfileValueResolver.resolve("unknown", "1", "2"))
    }
}
