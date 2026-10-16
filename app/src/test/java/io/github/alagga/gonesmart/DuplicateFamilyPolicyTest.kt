package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicateFamilyPolicyTest {

    private val base = TrackDuplicateIdentity(
        versionFamilyKeys = setOf("avicii|levels|BASE"),
        songIdentityKeys = setOf("avicii|levels"),
        isRemix = false
    )

    private val remix = TrackDuplicateIdentity(
        versionFamilyKeys = setOf("avicii|levels|REMIX:skrillex"),
        songIdentityKeys = setOf("avicii|levels"),
        isRemix = true
    )

    @Test
    fun versionDuplicatesDoNotBlockIndependentRemix() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(base, state)

        assertNull(
            DuplicateFamilyPolicy.conflictReason(
                candidate = remix,
                state = state,
                preventBaseVersionDuplicates = true,
                preventRemixDuplicates = false
            )
        )
    }

    @Test
    fun remixOptionBlocksRemixAgainstExistingBase() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(base, state)

        assertEquals(
            DuplicateConflictReason.REMIX,
            DuplicateFamilyPolicy.conflictReason(
                candidate = remix,
                state = state,
                preventBaseVersionDuplicates = false,
                preventRemixDuplicates = true
            )
        )
    }

    @Test
    fun remixOptionAlsoBlocksBaseAgainstExistingRemix() {
        val state = DuplicateFamilyState()
        DuplicateFamilyPolicy.add(remix, state)

        assertEquals(
            DuplicateConflictReason.REMIX,
            DuplicateFamilyPolicy.conflictReason(
                candidate = base,
                state = state,
                preventBaseVersionDuplicates = false,
                preventRemixDuplicates = true
            )
        )
    }
}
