package io.github.alagga.gonesmart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPickerCreateOnlyScopeTest {
    @Test fun eligibilityRequiresActiveUnselectedPickerAndInstalledNativeGuard() {
        val guard = PlaylistPickerCreateOnlyScope()
        assertTrue(guard.eligible(true, true, true, 0))
        assertFalse(guard.eligible(false, true, true, 0))
        assertFalse(guard.eligible(true, false, true, 0))
        assertFalse(guard.eligible(true, true, false, 0))
        assertFalse(guard.eligible(true, true, true, 2))
    }

    @Test fun suppressesOnlyPickerCloseDuringCreation() {
        val guard = PlaylistPickerCreateOnlyScope()
        assertFalse(guard.shouldSuppressClose("j83"))
        guard.duringCreate {
            assertTrue(guard.shouldSuppressClose("j83"))
            assertFalse(guard.shouldSuppressClose("q65"))
            assertFalse(guard.shouldSuppressClose(null))
        }
        assertFalse(guard.shouldSuppressClose("j83"))
    }

    @Test fun nestedScopesAndExceptionsRestorePriorState() {
        val guard = PlaylistPickerCreateOnlyScope()
        guard.duringCreate {
            try {
                guard.duringCreate {
                    assertTrue(guard.shouldSuppressClose("j83"))
                    throw IllegalStateException("test")
                }
            } catch (_: IllegalStateException) {
                assertTrue(guard.shouldSuppressClose("j83"))
            }
        }
        assertFalse(guard.shouldSuppressClose("j83"))
    }

    @Test fun unrelatedThreadsCannotLoseNativePickerBackEvent() {
        val guard = PlaylistPickerCreateOnlyScope()
        guard.duringCreate {
            val observed = java.util.concurrent.atomic.AtomicBoolean(true)
            val thread = Thread {
                observed.set(guard.shouldSuppressClose("j83"))
            }
            thread.start()
            thread.join()
            assertFalse(observed.get())
            assertTrue(guard.shouldSuppressClose("j83"))
        }
    }
}
