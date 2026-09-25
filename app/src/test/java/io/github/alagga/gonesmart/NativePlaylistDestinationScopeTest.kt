package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePlaylistDestinationScopeTest {
    private val root = "/storage/emulated/0/gmmp/playlists"
    private val folder = "$root/Progressive"

    @Test fun overridesOnlyTheExactNativeDelegateWithTheMatchingRoot() {
        val scope = NativePlaylistDestinationScope()
        val matching = Any()
        val unrelated = Any()
        val result = scope.withDestination(matching, root, folder) {
            assertEquals(root, scope.overrideNativeGetter(unrelated) { root })
            assertEquals(root + "/other", scope.overrideNativeGetter(matching) {
                root + "/other"
            })
            assertEquals(folder, scope.overrideNativeGetter(matching) { root })
            assertEquals("http://example.test", scope.overrideNativeGetter(matching) {
                "http://example.test"
            })
        }
        assertEquals(1, result.substitutions)
        assertEquals(root, scope.overrideNativeGetter(matching) { root })
    }

    @Test fun scopeIsThreadLocalEvenIfAnotherDelegateMatchesTheSamePath() {
        val scope = NativePlaylistDestinationScope()
        val matching = Any()
        scope.withDestination(matching, root, folder) {
            val otherThreadRead = java.util.concurrent.atomic.AtomicReference<String>()
            val thread = Thread {
                otherThreadRead.set(
                    scope.overrideNativeGetter(matching) { root } as String
                )
            }
            thread.start()
            thread.join()
            assertEquals(root, otherThreadRead.get())
            assertEquals(folder, scope.overrideNativeGetter(matching) { root })
        }
    }

    @Test fun nestedScopeAndExceptionCannotLeakIntoLaterNativeActions() {
        val scope = NativePlaylistDestinationScope()
        val delegate = Any()
        scope.withDestination(delegate, root, folder) {
            try {
                scope.withDestination(delegate, root, "$root/Other") {
                    assertEquals(
                        "$root/Other",
                        scope.overrideNativeGetter(delegate) { root }
                    )
                    throw IllegalStateException("test")
                }
            } catch (_: IllegalStateException) {
                assertEquals(folder, scope.overrideNativeGetter(delegate) { root })
            }
        }
        assertEquals(root, scope.overrideNativeGetter(delegate) { root })
    }
}
