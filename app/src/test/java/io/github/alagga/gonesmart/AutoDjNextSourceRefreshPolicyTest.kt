package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjNextSourceRefreshPolicyTest {
    private fun eligible(
        noUpcoming: Boolean = true,
        sawNull: Boolean = true,
        recovered: Boolean = false,
        verified: Boolean = true,
        current: Long? = 23,
        upcoming: Int = 1,
        active: Boolean? = true,
        manualNext: Int = 0,
        callback: Boolean = true,
        version: Boolean = true
    ) = AutoDjNextSourceRefreshPolicy.shouldRefresh(
        hadNoUpcomingAtRequest = noUpcoming,
        nativeNullSourceObserved = sawNull,
        nativeSourceAlreadyRecovered = recovered,
        nativeAppendVerified = verified,
        originalCurrentEntryId = 23,
        liveCurrentEntryId = current,
        liveUpcomingCount = upcoming,
        audioActive = active,
        pendingManualNextPresses = manualNext,
        sourceCallbackAvailable = callback,
        acceptedGmmpVersion = version
    )

    @Test fun `refreshes only a verified stale null with active current audio`() {
        assertTrue(eligible())
    }

    @Test fun `does not refresh when native next source was already present`() {
        assertFalse(eligible(sawNull = false))
        assertFalse(eligible(recovered = true))
        assertFalse(eligible(noUpcoming = false))
    }

    @Test fun `does not touch an incomplete or stale native queue`() {
        assertFalse(eligible(verified = false))
        assertFalse(eligible(current = 99))
        assertFalse(eligible(current = null))
        assertFalse(eligible(upcoming = 0))
    }

    @Test fun `does not replay source lookup after EOF or manual navigation`() {
        assertFalse(eligible(active = false))
        assertFalse(eligible(active = null))
        assertFalse(eligible(manualNext = 1))
    }

    @Test fun `unknown callback and other GMMP versions fail closed`() {
        assertFalse(eligible(callback = false))
        assertFalse(eligible(version = false))
    }
}
