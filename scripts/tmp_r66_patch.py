from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}\nANCHOR:\n{old}")
    p.write_text(text.replace(old, new, 1))

# 1) Native writer: separate native-dispatch scheduling budget from semantic
# readback verification. R66 device evidence shows the already verified GMMP
# executor can be blocked >2.3 s while the old single 1.5 s timeout expires.
path = "app/src/main/java/io/github/alagga/gonesmart/NativeQueuePositionWriterObserver.kt"
replace_once(
    path,
    "import java.util.concurrent.TimeUnit\n",
    "import java.util.concurrent.TimeUnit\nimport java.util.concurrent.TimeoutException\nimport java.util.concurrent.atomic.AtomicBoolean\n"
)
replace_once(
    path,
    "        private const val WRITE_TIMEOUT_MS = 1500L\n",
    "        private const val WRITE_DISPATCH_TIMEOUT_MS = 4_000L\n        private const val WRITE_READBACK_TIMEOUT_MS = 1_500L\n"
)
text = Path(path).read_text()
if text.count("SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS") != 2:
    raise SystemExit("unexpected writer readback timeout anchor count")
text = text.replace(
    "SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS",
    "SystemClock.elapsedRealtime() + WRITE_READBACK_TIMEOUT_MS"
)
Path(path).write_text(text)
old = '''        if (proof.looper != null) {
            if (Looper.myLooper() == proof.looper) return invoke()
            val done = CountDownLatch(1)
            var ok = false
            Handler(proof.looper).post {
                ok = invoke()
                done.countDown()
            }
            return done.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ok
        }

        val executor = nativeExecutor(autoDj)
        if (executor != null) {
            return runCatching {
                executor.submit<Boolean> { invoke() }
                    .get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        }
        return invoke()
'''
new = '''        if (proof.looper != null) {
            if (Looper.myLooper() == proof.looper) return invoke()
            val done = CountDownLatch(1)
            val started = AtomicBoolean(false)
            var ok = false
            val handler = Handler(proof.looper)
            val task = Runnable {
                started.set(true)
                try {
                    ok = invoke()
                } finally {
                    done.countDown()
                }
            }
            if (!handler.post(task)) return false
            if (done.await(WRITE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return ok
            }
            if (!started.get()) {
                handler.removeCallbacks(task)
                Log.w(
                    TAG,
                    "QUEUE POSITION DISPATCH TIMEOUT | writer=" +
                        proof.method.declaringClass.name + "." + proof.method.name +
                        " | route=looper | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                        " | started=false | cancelled=true"
                )
                return false
            }
            val finishedInGrace =
                done.await(WRITE_READBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.w(
                TAG,
                "QUEUE POSITION DISPATCH DELAY | writer=" +
                    proof.method.declaringClass.name + "." + proof.method.name +
                    " | route=looper | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                    " | started=true | finishedInGrace=$finishedInGrace"
            )
            return finishedInGrace && ok
        }

        val executor = nativeExecutor(autoDj)
        if (executor != null) {
            val started = AtomicBoolean(false)
            val future = executor.submit<Boolean> {
                started.set(true)
                invoke()
            }
            return try {
                future.get(WRITE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (!started.get()) {
                    val cancelled = future.cancel(false)
                    Log.w(
                        TAG,
                        "QUEUE POSITION DISPATCH TIMEOUT | writer=" +
                            proof.method.declaringClass.name + "." + proof.method.name +
                            " | route=executor | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                            " | started=false | cancelled=$cancelled"
                    )
                    false
                } else {
                    val finished = runCatching {
                        future.get(WRITE_READBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    Log.w(
                        TAG,
                        "QUEUE POSITION DISPATCH DELAY | writer=" +
                            proof.method.declaringClass.name + "." + proof.method.name +
                            " | route=executor | timeoutMs=$WRITE_DISPATCH_TIMEOUT_MS" +
                            " | started=true | finishedInGrace=${finished != null}"
                    )
                    finished ?: false
                }
            } catch (_: Throwable) {
                false
            }
        }
        return invoke()
'''
replace_once(path, old, new)

# 2) Pure policy for Track-Mix-owned no-match notices.
path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt"
replace_once(
    path,
    "    enum class NativeRefillAction { NORMAL, PASS_NATIVE, SUPPRESS }\n",
    "    enum class NativeRefillAction { NORMAL, PASS_NATIVE, SUPPRESS }\n    enum class NoMatchNoticeAction { PASS_THROUGH, DEFER, SUPPRESS_PREPARED, SHOW_OWNED }\n"
)
replace_once(
    path,
    '''    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)
''',
    '''    fun noMatchNoticeAction(
        hasPendingRequest: Boolean,
        stageDone: Boolean,
        preparationComplete: Boolean,
        targetResolved: Boolean,
        preparedTrackCount: Int
    ): NoMatchNoticeAction {
        if (!hasPendingRequest || stageDone) return NoMatchNoticeAction.PASS_THROUGH
        if (!preparationComplete) return NoMatchNoticeAction.DEFER
        if (!targetResolved) return NoMatchNoticeAction.PASS_THROUGH
        if (preparedTrackCount > 0) return NoMatchNoticeAction.SUPPRESS_PREPARED
        return NoMatchNoticeAction.SHOW_OWNED
    }

    fun nativeInitialRefillCount(initialSize: Int, seedQueueSize: Int): Int =
        (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0)).coerceAtLeast(0)
'''
)

# 3) Controller: do not route a no-match status through an unfinished/default
# empty preplay pool. Defer it until preparation is complete; then either show
# it only for a genuinely empty pool or consume it when prepared tracks exist.
path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
replace_once(
    path,
    "        @Volatile var prePlayPrepared: TrackMixPreparedInitialPool = TrackMixPreparedInitialPool.EMPTY\n        @Volatile var prePlayInitialSize = 0\n",
    "        @Volatile var prePlayPrepared: TrackMixPreparedInitialPool = TrackMixPreparedInitialPool.EMPTY\n        @Volatile var prePlayPreparationComplete = false\n        @Volatile var deferredNoMatchNotice = false\n        @Volatile var noMatchNoticeShown = false\n        @Volatile var prePlayInitialSize = 0\n"
)
old = '''    fun showOwnedStatusNotice(noticeKey: String): Boolean {
        if (noticeKey != "no-matches-native-fallback") return false
        val request = pending ?: return false
        if (request.prePlayTarget == null || request.stage == "DONE") return false

        Log.i(
            TAG,
            "MIX NOTICE | key=$noticeKey | route=owned-toast"
        )
        toast(
            request.context,
            NativeGmmpUiText.smartDjNotice(request.context, noticeKey)
        )
        return true
    }
'''
new = '''    fun showOwnedStatusNotice(noticeKey: String): Boolean {
        if (noticeKey != "no-matches-native-fallback") return false
        val request = pending
        val action = TrackMixInitialFillPolicy.noMatchNoticeAction(
            hasPendingRequest = request != null,
            stageDone = request?.stage == "DONE",
            preparationComplete = request?.prePlayPreparationComplete == true,
            targetResolved = request?.prePlayTarget != null,
            preparedTrackCount = request?.prePlayPrepared?.trackIds?.size ?: 0
        )
        when (action) {
            TrackMixInitialFillPolicy.NoMatchNoticeAction.PASS_THROUGH -> return false
            TrackMixInitialFillPolicy.NoMatchNoticeAction.DEFER -> {
                request?.deferredNoMatchNotice = true
                Log.i(TAG, "MIX NOTICE | key=$noticeKey | route=deferred-preplay")
                return true
            }
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SUPPRESS_PREPARED -> {
                request?.deferredNoMatchNotice = false
                Log.i(
                    TAG,
                    "MIX NOTICE | key=$noticeKey | route=suppressed-prepared" +
                        " | prepared=${request?.prePlayPrepared?.trackIds?.size ?: 0}"
                )
                return true
            }
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SHOW_OWNED -> Unit
        }
        request ?: return false
        if (request.noMatchNoticeShown) return true
        request.noMatchNoticeShown = true
        Log.i(
            TAG,
            "MIX NOTICE | key=$noticeKey | route=owned-toast | prepared=0"
        )
        toast(
            request.context,
            NativeGmmpUiText.smartDjNotice(request.context, noticeKey)
        )
        return true
    }
'''
replace_once(path, old, new)
replace_once(
    path,
    '''            }

            main.post {
                if (!isCurrent(request)) return@post
''',
    '''            }

            request.prePlayPreparationComplete = true
            if (request.deferredNoMatchNotice) {
                request.deferredNoMatchNotice = false
                showOwnedStatusNotice("no-matches-native-fallback")
            }

            main.post {
                if (!isCurrent(request)) return@post
'''
)

# 4) Regression policy tests for the Toast race.
path = "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt"
replace_once(
    path,
    "    @Test fun slotRefillRequestsOnlyRealShortfall() {\n",
    '''    @Test fun noMatchNoticeWaitsForActualPreplayResult() {
        assertEquals(
            TrackMixInitialFillPolicy.NoMatchNoticeAction.DEFER,
            TrackMixInitialFillPolicy.noMatchNoticeAction(
                hasPendingRequest = true,
                stageDone = false,
                preparationComplete = false,
                targetResolved = true,
                preparedTrackCount = 0
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SUPPRESS_PREPARED,
            TrackMixInitialFillPolicy.noMatchNoticeAction(
                hasPendingRequest = true,
                stageDone = false,
                preparationComplete = true,
                targetResolved = true,
                preparedTrackCount = 4
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NoMatchNoticeAction.SHOW_OWNED,
            TrackMixInitialFillPolicy.noMatchNoticeAction(
                hasPendingRequest = true,
                stageDone = false,
                preparationComplete = true,
                targetResolved = true,
                preparedTrackCount = 0
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NoMatchNoticeAction.PASS_THROUGH,
            TrackMixInitialFillPolicy.noMatchNoticeAction(
                hasPendingRequest = false,
                stageDone = false,
                preparationComplete = false,
                targetResolved = false,
                preparedTrackCount = 0
            )
        )
    }

    @Test fun slotRefillRequestsOnlyRealShortfall() {
'''
)

# 5) Permanent compatibility record.
path = "AGENTS.md"
replace_once(
    path,
    "\n## 6. Playlist / Smart-Playlist / UI rules\n",
    '''
- R66 device evidence: a previously verified 4.2.1 Current-position writer can be queued behind GMMP worker contention for more than the old 1.5 s dispatch budget. Keep semantic readback verification strict, but allow a separate bounded 4 s native-dispatch wait and cancel work that has not started when that budget expires. A dispatch timeout is scheduling evidence, not permission to guess or invoke another writer. Track-Mix `no-matches-native-fallback` status is owned only after preplay preparation finishes; defer it while the pool is unresolved and suppress it when prepared Track IDs exist.

## 6. Playlist / Smart-Playlist / UI rules
'''
)

path = "docs/GMMP_COMPATIBILITY_PLAYBOOK.md"
p = Path(path)
text = p.read_text().rstrip()
text += '''\n\n### 2026-10-17 R66 queued writer dispatch + no-match notice gating\n\nDevice evidence after R65 showed three successful Track-Mix starts and one prepared Queue-context failure. The failed run had 14 suitable local candidates and four prepared Track IDs, but the already passively verified `dx3.c2(int)` Current writer did not begin/finish inside the shared 1.5 s controlled-dispatch budget while GMMP logged >2.3 s worker contention. R66 separates native scheduling from semantic verification: controlled dispatch may wait up to 4 s on the proven native looper/executor, while post-invocation readback remains 1.5 s and unchanged. Queued work that has not started is removed/cancelled on timeout; no alternate writer is guessed.\n\nThe same log contained two genuine zero-prepared starts and two Queue starts with four prepared matches. Track Mix now defers `no-matches-native-fallback` while preplay is unresolved, suppresses it if the completed pool is non-empty, and shows the owned notice only after a completed empty preplay result.\n'''
p.write_text(text + "\n")

# Scope/format checks performed again by workflow.
