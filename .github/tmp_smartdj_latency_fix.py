from pathlib import Path

# SessionRecommendationPool: throttle only genuinely unproductive provider fills.
p = Path('app/src/main/java/io/github/alagga/gonesmart/SessionRecommendationPool.kt')
s = p.read_text()
s = s.replace(
'''        private const val MIN_REFILL_ATTEMPT_INTERVAL_MS =
            60_000L
''',
'''        private const val UNPRODUCTIVE_REFILL_BACKOFF_MS =
            60_000L
''', 1)
s = s.replace(
'''    private var lastRefillAttemptElapsedMs =
        Long.MIN_VALUE
''',
'''    private var lastUnproductiveFillElapsedMs =
        Long.MIN_VALUE
''', 1)
s = s.replace(
'''        lastRefillAttemptElapsedMs =
            Long.MIN_VALUE
''',
'''        lastUnproductiveFillElapsedMs =
            Long.MIN_VALUE
''', 1)
old = '''        val now =
            SystemClock.elapsedRealtime()

        if (
            lastRefillAttemptElapsedMs !=
            Long.MIN_VALUE &&
            now -
                lastRefillAttemptElapsedMs <
                MIN_REFILL_ATTEMPT_INTERVAL_MS
        ) {

            return false
        }

        return true
    }

    @Synchronized
    fun markRefillAttempt(
        expectedSessionId: Long
    ) {

        if (
            sessionId ==
            expectedSessionId
        ) {

            lastRefillAttemptElapsedMs =
                SystemClock.elapsedRealtime()
        }
    }
'''
new = '''        val now =
            SystemClock.elapsedRealtime()

        // Back off only when the previous provider pass produced nothing.
        // A successful pool that is being consumed quickly is precisely the
        // case where we want an early asynchronous top-up rather than making
        // the next native Auto-DJ request wait for network work.
        if (
            lastUnproductiveFillElapsedMs !=
            Long.MIN_VALUE &&
            now -
                lastUnproductiveFillElapsedMs <
                UNPRODUCTIVE_REFILL_BACKOFF_MS
        ) {

            return false
        }

        return true
    }

    @Synchronized
    fun recordRefillResult(
        expectedSessionId: Long,
        addedCount: Int
    ) {

        if (
            sessionId !=
            expectedSessionId
        ) {

            return
        }

        lastUnproductiveFillElapsedMs =
            if (addedCount > 0) {
                Long.MIN_VALUE
            } else {
                SystemClock.elapsedRealtime()
            }
    }
'''
assert old in s, 'pool refill throttle block changed unexpectedly'
s = s.replace(old, new, 1)
p.write_text(s)

# Pool sizing: give the network pipeline more runway during rapid skipping.
p = Path('app/src/main/java/io/github/alagga/gonesmart/GmmpAutoDjSettingsReader.kt')
s = p.read_text()
s = s.replace(
'''         * upcoming=1), this produces a target of 20 and a
         * low-water mark of 6.
''',
'''         * upcoming=1), this produces a target of 20 and a
         * low-water mark of 10. Refilling at half-full gives the
         * provider/matcher pipeline enough runway during rapid skips.
''', 1)
s = s.replace(
'''                    settings.upcomingTrackCount * 2,
                    ceil(
                        target * 0.30
                    ).toInt()
''',
'''                    settings.upcomingTrackCount * 3,
                    ceil(
                        target * 0.50
                    ).toInt()
''', 1)
s = s.replace(
'''            max(
                3,
                settings.upcomingTrackCount * 2
            )
''',
'''            max(
                2,
                settings.upcomingTrackCount
            )
''', 1)
p.write_text(s)

# Smart DJ module: record fill productivity, prewarm first recommendation pool,
# and expose phase timings so any remaining delay is measurable in one log.
p = Path('app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt')
s = p.read_text()

# Remove generic attempt timestamping and record the actual result instead.
s = s.replace(
'''            recommendationPool
                .markRefillAttempt(
                    session.sessionId
                )

''', '', 1)
needle = '''                        val added =
                            recommendationPool
                                .mergeCandidates(
                                    expectedSessionId = session.sessionId,
                                    candidateTrackIds = candidateTrackIds,
                                    newTargetSize = sizing.targetSize
                                )

                        Log.i(
'''
replacement = '''                        val added =
                            recommendationPool
                                .mergeCandidates(
                                    expectedSessionId = session.sessionId,
                                    candidateTrackIds = candidateTrackIds,
                                    newTargetSize = sizing.targetSize
                                )

                        recommendationPool.recordRefillResult(
                            expectedSessionId = session.sessionId,
                            addedCount = added
                        )

                        Log.i(
'''
assert needle in s, 'pool merge block changed unexpectedly'
s = s.replace(needle, replacement, 1)

# After the local library index is ready, start warming the first recommendation
# pool in the background. This is read-only until GMMP later calls its native
# Auto-DJ refill/selection boundary.
needle = '''                        Log.i(
                            TAG,
                            "STARTUP PREWARM READY | " +
                                "library=${library.size} | " +
                                "index=$preparationResult"
                        )

                        success =
                            true

                        break
'''
replacement = '''                        Log.i(
                            TAG,
                            "STARTUP PREWARM READY | " +
                                "library=${library.size} | " +
                                "index=$preparationResult"
                        )

                        scheduleInitialRecommendationPoolPrewarm(
                            autoDjInstance
                        )

                        success =
                            true

                        break
'''
assert needle in s, 'startup prewarm success block changed unexpectedly'
s = s.replace(needle, replacement, 1)

# Add the background recommendation prewarm helper before isAutoDjLibraryReady.
anchor = '''    private fun isAutoDjLibraryReady(
        autoDjInstance: Any
    ): Boolean {
'''
assert anchor in s, 'isAutoDjLibraryReady anchor missing'
helper = '''    private fun scheduleInitialRecommendationPoolPrewarm(
        autoDjInstance: Any
    ) {
        if (!options.enabled) return
        if (networkStateReader.getState() != GoneSmartNetworkState.ONLINE) return

        startupExecutor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            val queueContext = queueReader.read(autoDjInstance) ?: return@execute
            val session = queueSessionTracker.observe(queueContext)
            val settings = gmmpAutoDjSettingsReader.read()
            val sizing = gmmpAutoDjSettingsReader.calculatePoolSizing(settings)

            if (
                session.isNewSession ||
                !recommendationPool.isForSession(session.sessionId)
            ) {
                resetRecommendationPoolForSession(session, sizing)
            } else {
                recommendationPool.configureTarget(
                    expectedSessionId = session.sessionId,
                    newTargetSize = sizing.targetSize
                )
            }

            if (
                recommendationPool.hasEnough(
                    expectedSessionId = session.sessionId,
                    count = 1,
                    excludedTrackIds = queueContext.items.map { it.track.id }.toSet()
                )
            ) {
                return@execute
            }

            val seeds = seedSelector.select(session)
            if (seeds.isEmpty()) return@execute

            Log.i(
                TAG,
                "SMART DJ INITIAL POOL PREWARM | session=${session.sessionId} | " +
                    "target=${sizing.targetSize} | seeds=${seeds.size}"
            )
            startPoolFill(
                seeds = seeds,
                autoDjInstance = autoDjInstance,
                queueContext = queueContext,
                session = session,
                sizing = sizing,
                background = true
            )
            Log.i(
                TAG,
                "SMART DJ INITIAL POOL PREWARM SCHEDULED | ms=" +
                    (SystemClock.elapsedRealtime() - startedAt)
            )
        }
    }

'''
s = s.replace(anchor, helper + anchor, 1)

# Timing markers around the refill hook. No additional queue reads are added.
needle = '''            val requestedTracks =
                (chain.getArg(
                    0
                ) as? Int)
                    ?.coerceAtLeast(
                        1
                    )
                    ?: 1

            val autoDjInstance =
'''
replacement = '''            val requestedTracks =
                (chain.getArg(
                    0
                ) as? Int)
                    ?.coerceAtLeast(
                        1
                    )
                    ?: 1

            val refillStartedAt = SystemClock.elapsedRealtime()
            var timingCheckpoint = refillStartedAt
            fun logRefillTiming(phase: String) {
                val now = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "SMART DJ TIMING | phase=$phase | deltaMs=" +
                        (now - timingCheckpoint) +
                        " | totalMs=" + (now - refillStartedAt) +
                        " | requested=$requestedTracks"
                )
                timingCheckpoint = now
            }

            val autoDjInstance =
'''
assert needle in s, 'refill requestedTracks block changed unexpectedly'
s = s.replace(needle, replacement, 1)

needle = '''            val beforeContext =
                queueReader.read(
                    autoDjInstance
                )

            if (
'''
replacement = '''            val beforeContext =
                queueReader.read(
                    autoDjInstance
                )
            logRefillTiming("queue-read-before")

            if (
'''
assert needle in s
s = s.replace(needle, replacement, 1)

needle = '''            if (
                !poolReady
            ) {
'''
replacement = '''            logRefillTiming(
                if (poolAlreadyReady) "pool-hit" else "pool-ready-after-fill"
            )

            if (
                !poolReady
            ) {
'''
assert needle in s
s = s.replace(needle, replacement, 1)

needle = '''            val result =
                try {

                    autoDjSelectionWindow.set(
'''
replacement = '''            val result =
                try {

                    autoDjSelectionWindow.set(
'''
# Keep source shape unchanged here; timing is inserted after the try/finally.
assert needle in s

needle = '''                } finally {

                    autoDjSelectionWindow.set(
                        null
                    )
                }

            val afterContext =
'''
replacement = '''                } finally {

                    autoDjSelectionWindow.set(
                        null
                    )
                }
            logRefillTiming("native-refill")

            val afterContext =
'''
assert needle in s
s = s.replace(needle, replacement, 1)

needle = '''            val afterContext =
                queueReader.read(
                    autoDjInstance
                )

            if (
                afterContext != null
            ) {
'''
replacement = '''            val afterContext =
                queueReader.read(
                    autoDjInstance
                )
            logRefillTiming("queue-read-after")

            if (
                afterContext != null
            ) {
'''
# There are several fallback branches with the same text. Replace the LAST
# occurrence, which is the successful Smart path after native refill.
pos = s.rfind(needle)
assert pos >= 0, 'successful afterContext block missing'
s = s[:pos] + replacement + s[pos + len(needle):]

needle = '''            Log.i(
                TAG,
                "SMART DJ POOL STATE | " +
                    recommendationPool.describe(
                        session.sessionId
                    )
            )

            Log.i(
'''
replacement = '''            Log.i(
                TAG,
                "SMART DJ POOL STATE | " +
                    recommendationPool.describe(
                        session.sessionId
                    )
            )
            logRefillTiming("complete")

            Log.i(
'''
assert needle in s
s = s.replace(needle, replacement, 1)

p.write_text(s)

# Durable playbook notes.
p = Path('AGENTS.md')
s = p.read_text()
addition = '''- Smart Auto-DJ provider backoff is for **unproductive** recommendation fills, not successful fills. A successfully consumed session pool must be allowed to top up asynchronously before it empties; never make rapid skipping wait solely because an earlier successful fill happened less than a minute ago.
- Keep the GoneSmart recommendation pool ahead of GMMP's visible Auto-DJ queue. Prefer background recommendation prewarm/low-water top-up over speculative early mutation of GMMP's playback queue. Native queue insertion remains owned by GMMP's verified Auto-DJ refill path.
- Smart Auto-DJ latency diagnostics must distinguish queue-read-before, pool hit/fill, native refill, queue-read-after and total time so future regressions can be localized without multiple probe APKs.
'''
if addition not in s:
    if not s.endswith('\n'):
        s += '\n'
    s += addition
p.write_text(s)
