package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AutoDjSelectionIdentityPolicyTest {
    @Test
    fun aliasedNativeSelectionRowsAreNeverWrittenTwice() {
        val shared = Any()
        val distinct = Any()
        val unique = AutoDjSelectionIdentityPolicy.uniqueRows(
            listOf(shared, shared, distinct, shared)
        )

        assertEquals(2, unique.size)
        assertSame(shared, unique[0])
        assertSame(distinct, unique[1])
    }

    @Test
    fun equalButDifferentNativeInstancesRemainIndependent() {
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        assertEquals(2, AutoDjSelectionIdentityPolicy.uniqueRows(listOf(first, second)).size)
    }
}
