from pathlib import Path
import re


def sub_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, got {count}')
    return updated

controller_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt')
controller = controller_path.read_text()
controller = controller.replace('import java.util.concurrent.CopyOnWriteArrayList\n', '', 1)

controller = sub_once(
    controller,
    r'''    private data class ObservedNativePositionCommand\(.*?\n    private data class Pending\(''',
    '''    private data class Pending(''',
    'remove stale position-command model',
)

old_pending = '''        val nativePositionCommands = CopyOnWriteArrayList<ObservedNativePositionCommand>()
        @Volatile var provisionalCurrentQueuePosition: Int? = null
        @Volatile var nextSourceBoundary: ObservedNativePositionCommand? = null
        @Volatile var nextSourceBoundaryAmbiguous = false
        @Volatile var zeroPoolBoundaryAttempted = false
'''
new_pending = '''        @Volatile var provisionalCurrentQueuePosition: Int? = null
        @Volatile var acceptedNextSourceObserved = false
        @Volatile var zeroPoolBoundaryAttempted = false
'''
if old_pending not in controller:
    raise SystemExit('Pending next-source fields not found')
controller = controller.replace(old_pending, new_pending, 1)
controller = controller.replace(
    '        @Volatile var zeroPoolBoundaryPrepared = false\n',
    '        @Volatile var zeroPoolBoundaryPrepared = false\n        @Volatile var zeroPoolTailFallback = false\n',
    1,
)

new_observer = r'''    /**
     * R70 accepted-version next-source boundary. Device traces across R49,
     * R62/R64 and the 2026-10-17 position-2 file-browser failure prove that
     * MusicService.I2(int) is the natural 4.2.1 next-source materialization
     * callback, but its int argument is NOT queue_position: Current=2 still
     * invoked I2(2) and the original method then queried queue_position=3.
     *
     * The tested alias/signature therefore supplies event identity only. Any
     * mutation still requires the exact pre-Play target to be the unique live
     * Current row and the independently verified writer/readback to agree on
     * its absolute queue_position. The guarded follower is derived as
     * Current+1 from that proof, never from the obfuscated argument.
     */
    fun observeNativePositionCommand(
        service: Any?,
        method: java.lang.reflect.Method,
        value: Int?
    ) {
        val request = pending ?: return
        if (request.stage != "WAIT_PLAY") return
        if (request.prePlayPrepared.trackIds.isNotEmpty()) return
        val target = request.prePlayTarget ?: return
        if (service == null || value == null) return
        if (!TrackMixZeroPoolPolicy.isAcceptedGmmp421NextSourceCommand(
                declaringClassName = method.declaringClass.name,
                methodName = method.name,
                parameterTypeName = method.parameterTypes.singleOrNull()?.name,
                returnTypeName = method.returnType.name
            )
        ) return
        val isMusicService = generateSequence<Class<*>>(service.javaClass) {
            it.superclass
        }.any { it.name == "gonemad.gmmp.playback.service.MusicService" }
        if (!isMusicService) return

        request.acceptedNextSourceObserved = true
        val autoDj = nativeAutoDj?.get() ?: return
        val located = runCatching {
            queueReader.locateUniqueTrackRow(autoDj, target.id)
        }.getOrNull() ?: return
        val writer = nativePositionWriterProvider(autoDj)
            ?: nativeTrackMixPositionWriterProvider(autoDj, located.queuePosition)
            ?: return
        val readback = runCatching { writer.read() }.getOrNull() ?: return
        val context = runCatching {
            queueReader.read(
                autoDj,
                readback,
                located.queueEntryId
            )
        }.getOrNull() ?: return
        val current = context.currentItem ?: return
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = target.id,
            currentTrackId = current.track.id,
            selectedQueuePosition = located.queuePosition,
            currentQueuePosition = context.currentQueuePosition,
            writerReadback = readback
        ) ?: run {
            Log.i(
                TAG,
                "MIX NEXT SOURCE CURRENT PROOF | verified=false" +
                    " | seed=${target.id}" +
                    " | entry=${located.queueEntryId}" +
                    " | selectedPosition=${located.queuePosition}" +
                    " | currentPosition=${context.currentQueuePosition}" +
                    " | writerReadback=$readback" +
                    " | observedArg=$value"
            )
            return
        }
        if (current.queueEntryId != located.queueEntryId) return

        request.verifiedCurrentQueueEntryId = located.queueEntryId
        request.provisionalCurrentQueuePosition = boundary.currentQueuePosition
        Log.i(
            TAG,
            "MIX NEXT SOURCE BOUNDARY | captured=true" +
                " | command=${method.declaringClass.name}.${method.name}(int)" +
                " | observedArg=$value" +
                " | currentPosition=${boundary.currentQueuePosition}" +
                " | nextPosition=${boundary.nextQueuePosition}" +
                " | evidence=accepted-method+writer+exact-row"
        )

        if (!request.zeroPoolBoundaryPrepared) {
            prepareZeroPoolAtNaturalCurrentBoundary(
                autoDj = autoDj,
                currentPosition = boundary.currentQueuePosition
            )
            if (!request.zeroPoolBoundaryPrepared) {
                Log.e(
                    TAG,
                    "MIX ZERO-POOL NEXT SOURCE PREPARE | " +
                        "verified Current boundary unavailable; fail closed"
                )
                return
            }
            Log.i(
                TAG,
                "MIX ZERO-POOL NEXT SOURCE PREPARE | verified=true | " +
                    "beforeOriginal=true | source=accepted-natural-next-source | " +
                    "currentPosition=${boundary.currentQueuePosition} | " +
                    "nextPosition=${boundary.nextQueuePosition} | observedArg=$value"
            )
        }

        guardZeroPoolNaturalNextSource(
            request = request,
            method = method
        )
    }

    private fun guardZeroPoolNaturalNextSource(
        request: Pending,
        method: java.lang.reflect.Method
    ) {
        if (request.zeroPoolNextSourceGuardAttempted) return
        if (!request.acceptedNextSourceObserved) return
        if (request.prePlayPrepared.trackIds.isNotEmpty()) return
        if (request.stage != "WAIT_PLAY" || !request.zeroPoolBoundaryPrepared) return
        if (request.prePlayTarget == null) return
        val autoDj = nativeAutoDj?.get() ?: return
        val currentPosition = request.provisionalCurrentQueuePosition ?: return
        val plan = runCatching {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = currentPosition,
                nativeFallbackTrackIds = request.zeroPoolNativeFallbackTrackIds
            )
        }.getOrNull() ?: return

        request.zeroPoolNextSourceGuardAttempted = true
        val target = request.prePlayTarget ?: return
        val applied = runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ).guardTrackMixZeroPoolNextSource(
                selectedTrackId = target.id,
                nextQueuePosition = plan.nextQueuePosition,
                expectedTrackId = plan.expectedTrackId
            )
        }.onFailure {
            Log.e(
                TAG,
                "MIX ZERO-POOL NEXT GUARD | pre-source queue repair failed; fail closed",
                it
            )
        }.getOrDefault(false)
        request.zeroPoolNextSourceGuardApplied = applied
        Log.i(
            TAG,
            "MIX ZERO-POOL NEXT GUARD | applied=$applied | " +
                "position=${plan.nextQueuePosition} | track=${plan.expectedTrackId} | " +
                "command=${method.declaringClass.name}.${method.name}(int) | " +
                "beforeOriginal=true | positionSource=verified-current-plus-one"
        )
    }

'''
controller = sub_once(
    controller,
    r'''    /\*\*\n     \* Record bounded natural MusicService Int->void calls.*?(?=    /\*\*\n     \* r45 bounded read-only mapping)''',
    new_observer,
    'replace stale argument-based next-source observer',
)

controller = sub_once(
    controller,
    r'''    private fun tryPromoteNextSourceBoundary\(request: Pending\) \{.*?(?=    private fun prepareZeroPoolFromNaturalStateCandidate)''',
    '',
    'remove stale next-source promotion helper',
)
controller = controller.replace('                        tryPromoteNextSourceBoundary(request)\n', '')

old_writer = '''        val currentWriter = nativeTrackMixPositionWriterProvider(
            autoDj,
            position
        ) ?: run {
'''
new_writer = '''        val currentWriter = nativePositionWriterProvider(autoDj)
            ?: nativeTrackMixPositionWriterProvider(
                autoDj,
                position
            ) ?: run {
'''
if old_writer not in controller:
    raise SystemExit('natural Current writer block not found')
controller = controller.replace(old_writer, new_writer, 1)

controller = controller.replace(
    '"awaiting native qr.z or current+1 predecoder boundary"',
    '"awaiting native qr.z or verified next-source predecoder boundary"',
    1,
)

old_predecoder = '''            if (usePredecoderCompatibility) {
                val filled = preparePredecoderInitialQueue(
'''
new_predecoder = '''            if (usePredecoderCompatibility) {
                if (request.prePlayPrepared.trackIds.isEmpty() &&
                    TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                        source = request.source,
                        currentResolutionSource = loaded.currentResolutionSource,
                        preparedTrackCount = request.prePlayPrepared.trackIds.size,
                        boundaryPrepared = request.zeroPoolBoundaryPrepared,
                        boundaryAttempted = request.zeroPoolBoundaryAttempted,
                        nextSourceObserved = request.acceptedNextSourceObserved,
                        currentIndex = loaded.currentIndex,
                        queueSize = loaded.ids.size,
                        currentQueuePosition = loaded.currentQueuePosition,
                        currentTrackId = loaded.currentId,
                        selectedTrackId = selectedId
                    )
                ) {
                    request.zeroPoolTailFallback = true
                    val tailAutoDj = nativeAutoDj?.get()
                    val tailBoundary = tailAutoDj?.let { owner ->
                        prepareZeroPoolAtNativeRefill(
                            autoDj = owner,
                            requestedTracks = 0
                        )
                    }
                    val tailPrepared = tailAutoDj != null &&
                        tailBoundary != null &&
                        completeZeroPoolAtNativeRefill(tailAutoDj, tailBoundary)
                    if (tailPrepared) {
                        Log.i(
                            TAG,
                            "MIX ZERO-POOL TAIL FALLBACK | verified=true | " +
                                "source=queue-context-target | seed=$selectedId | " +
                                "oldCurrentPosition=${loaded.currentQueuePosition} | " +
                                "currentPosition=1 | beforeNextSource=true"
                        )
                    } else {
                        Log.e(
                            TAG,
                            "MIX ZERO-POOL TAIL FALLBACK | preparation failed; fail closed"
                        )
                    }
                }

                val filled = preparePredecoderInitialQueue(
'''
if old_predecoder not in controller:
    raise SystemExit('predecoder compatibility branch anchor not found')
controller = controller.replace(old_predecoder, new_predecoder, 1)

old_refill_log = '''            request.provisionalCurrentQueuePosition = 1
            Log.i(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | isolated=true | beforeOriginal=true" +
                    " | seed=${target.id} | entry=${located.queueEntryId}" +
                    " | oldCurrentPosition=${located.queuePosition} | currentPosition=1" +
                    " | originalRequested=$requestedTracks | topUp=${plan.topUpCount}" +
                    " | route=predecoder-4.2.0-semantics"
            )
'''
new_refill_log = '''            request.provisionalCurrentQueuePosition = 1
            val trigger = if (request.zeroPoolTailFallback) {
                "queue-tail"
            } else {
                "native-refill"
            }
            val beforeOriginal = !request.zeroPoolTailFallback
            Log.i(
                TAG,
                "MIX ZERO-POOL REFILL BOUNDARY | isolated=true | beforeOriginal=$beforeOriginal" +
                    " | seed=${target.id} | entry=${located.queueEntryId}" +
                    " | oldCurrentPosition=${located.queuePosition} | currentPosition=1" +
                    " | originalRequested=$requestedTracks | topUp=${plan.topUpCount}" +
                    " | trigger=$trigger | route=predecoder-4.2.0-semantics"
            )
'''
if old_refill_log not in controller:
    raise SystemExit('zero-pool refill log anchor not found')
controller = controller.replace(old_refill_log, new_refill_log, 1)

old_source = '''            val boundarySource = if (boundary.originalRequested == 0) {
                "natural-current-plus-one-fallback"
            } else {
                "natural-native-refill"
            }
'''
new_source = '''            val boundarySource = when {
                request.zeroPoolTailFallback -> "queue-tail-pre-next-source-fallback"
                boundary.originalRequested == 0 -> "natural-next-source-fallback"
                else -> "natural-native-refill"
            }
'''
if old_source not in controller:
    raise SystemExit('zero-pool boundary source block not found')
controller = controller.replace(old_source, new_source, 1)
controller_path.write_text(controller.rstrip('\n') + '\n')

policy_path = Path('app/src/main/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicy.kt')
policy = policy_path.read_text()
policy = sub_once(
    policy,
    r'''    data class PositionCommandCandidate\(.*?(?=    data class SeedPlan\()''',
    '',
    'remove stale argument boundary policy',
)

new_proof = r'''    data class VerifiedNextSourceBoundary(
        val currentQueuePosition: Int,
        val nextQueuePosition: Int
    )

    /**
     * Accepted GMMP 4.2.1 event alias. The name/signature is allowed to
     * identify a passive next-source callback only because repeated device
     * traces correlate the natural I2 invocation with the immediately nested
     * queue_position lookup. Mutation authority still comes exclusively from
     * resolveVerifiedNextSourceBoundary below.
     */
    fun isAcceptedGmmp421NextSourceCommand(
        declaringClassName: String,
        methodName: String,
        parameterTypeName: String?,
        returnTypeName: String?
    ): Boolean =
        declaringClassName == "gonemad.gmmp.playback.service.MusicService" &&
            methodName == "I2" &&
            parameterTypeName == "int" &&
            returnTypeName == "void"

    /**
     * R70 proof for a natural next-source event. The obfuscated method
     * argument is deliberately absent: the selected row, independently
     * resolved Current, and verified writer/readback must all agree on one
     * positive absolute queue_position. Next is derived from that coordinate.
     */
    fun resolveVerifiedNextSourceBoundary(
        selectedTrackId: Long,
        currentTrackId: Long?,
        selectedQueuePosition: Int,
        currentQueuePosition: Int?,
        writerReadback: Int?
    ): VerifiedNextSourceBoundary? {
        if (selectedTrackId <= 0L || currentTrackId != selectedTrackId) return null
        val current = currentQueuePosition ?: return null
        if (current <= 0 || current == Int.MAX_VALUE) return null
        if (selectedQueuePosition != current || writerReadback != current) return null
        return VerifiedNextSourceBoundary(
            currentQueuePosition = current,
            nextQueuePosition = current + 1
        )
    }

'''
policy = sub_once(
    policy,
    r'''    /\*\*\n     \* R64 request-local proof.*?(?=    data class StateWriterMapping\()''',
    new_proof,
    'replace R64 argument-based proof',
)

new_guard = r'''    data class NaturalNextSourceGuardPlan(
        val nextQueuePosition: Int,
        val expectedTrackId: Long
    )

    /**
     * R70 pre-source contract. The next queue position is always derived from
     * the independently verified Current coordinate, never from I2(int)'s
     * opaque argument.
     */
    fun naturalNextSourceGuardPlan(
        currentQueuePosition: Int,
        nativeFallbackTrackIds: List<Long>
    ): NaturalNextSourceGuardPlan {
        require(currentQueuePosition > 0 && currentQueuePosition < Int.MAX_VALUE) {
            "Zero-pool Current queue position must be positive and incrementable"
        }
        require(nativeFallbackTrackIds.isNotEmpty()) {
            "Zero-pool native fallback has no current+1 Track"
        }
        require(nativeFallbackTrackIds.all { it > 0L }) {
            "Zero-pool native fallback Track IDs must be positive"
        }
        return NaturalNextSourceGuardPlan(
            nextQueuePosition = currentQueuePosition + 1,
            expectedTrackId = nativeFallbackTrackIds.first()
        )
    }

    /**
     * If Queue Play selects the last native row, GMMP has no existing follower
     * that can force the natural next-source callback before Track Mix's worker
     * verifies the selection. R70 allows the already accepted position-1
     * zero-pool native fill only for that exact tail case and only if no
     * next-source event or other zero-pool boundary has already occurred.
     */
    fun shouldPrepareQueueTailFallback(
        source: String,
        currentResolutionSource: String?,
        preparedTrackCount: Int,
        boundaryPrepared: Boolean,
        boundaryAttempted: Boolean,
        nextSourceObserved: Boolean,
        currentIndex: Int,
        queueSize: Int,
        currentQueuePosition: Int?,
        currentTrackId: Long?,
        selectedTrackId: Long
    ): Boolean {
        if (source != "menu_gm_context_queue") return false
        if (currentResolutionSource != "queue-context-target") return false
        if (preparedTrackCount != 0 || boundaryPrepared || boundaryAttempted) return false
        if (nextSourceObserved) return false
        if (selectedTrackId <= 0L || currentTrackId != selectedTrackId) return false
        if (queueSize <= 0 || currentIndex != queueSize - 1) return false
        return currentQueuePosition != null && currentQueuePosition > 0
    }

'''
policy = sub_once(
    policy,
    r'''    data class NaturalNextSourceGuardPlan\(.*?(?=    data class SourceBatchEntry\()''',
    new_guard,
    'replace natural next-source guard policy',
)
policy_path.write_text(policy.rstrip('\n') + '\n')

test_path = Path('app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt')
test = test_path.read_text()
test = test.replace(
    'import org.junit.Assert.assertNotNull\n',
    'import org.junit.Assert.assertNotNull\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\n',
    1,
)
new_first_tests = r'''    @Test
    fun `accepted 421 next source callback is the exact tested passive shape`() {
        assertTrue(
            TrackMixZeroPoolPolicy.isAcceptedGmmp421NextSourceCommand(
                declaringClassName = "gonemad.gmmp.playback.service.MusicService",
                methodName = "I2",
                parameterTypeName = "int",
                returnTypeName = "void"
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.isAcceptedGmmp421NextSourceCommand(
                declaringClassName = "gonemad.gmmp.playback.service.MusicService",
                methodName = "I3",
                parameterTypeName = "int",
                returnTypeName = "void"
            )
        )
    }

    @Test
    fun `next source proof derives position from verified Current not opaque argument`() {
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = 2,
            currentQueuePosition = 2,
            writerReadback = 2
        )
        assertNotNull(boundary)
        assertEquals(2, boundary!!.currentQueuePosition)
        assertEquals(3, boundary.nextQueuePosition)
    }

    @Test
    fun `next source proof rejects identity or coordinate disagreement`() {
        assertNull(
            TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
                selectedTrackId = 18165L,
                currentTrackId = 18166L,
                selectedQueuePosition = 2,
                currentQueuePosition = 2,
                writerReadback = 2
            )
        )
        assertNull(
            TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
                selectedTrackId = 18165L,
                currentTrackId = 18165L,
                selectedQueuePosition = 2,
                currentQueuePosition = 2,
                writerReadback = 1
            )
        )
    }

'''
test = sub_once(
    test,
    r'''    @Test\n    fun `promotes only the unique recent current plus one position command`.*?(?=    @Test\n    fun `track mix state writer maps zero based argument and signal to queue position`)''',
    new_first_tests,
    'replace obsolete argument-boundary tests',
)

new_guard_tests = r'''    @Test
    fun `natural source guard derives first follower from verified Current`() {
        val plan = TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
            currentQueuePosition = 6,
            nativeFallbackTrackIds = listOf(10106L, 653L)
        )
        assertEquals(7, plan.nextQueuePosition)
        assertEquals(10106L, plan.expectedTrackId)
    }

    @Test
    fun `natural source guard refuses invalid current or missing fallback identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 0,
                nativeFallbackTrackIds = listOf(10106L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackMixZeroPoolPolicy.naturalNextSourceGuardPlan(
                currentQueuePosition = 1,
                nativeFallbackTrackIds = emptyList()
            )
        }
    }

'''
test = sub_once(
    test,
    r'''    @Test\n    fun `natural source guard uses only the persisted first native fallback identity`.*?(?=    @Test\n    fun `late source rebuild fails closed when the predecode fallback identity is incomplete`)''',
    new_guard_tests,
    'replace stale natural source guard tests',
)

new_bootstrap_test = r'''    @Test
    fun `verified natural next source can bootstrap initial size when natural refill was absent`() {
        val boundary = TrackMixZeroPoolPolicy.resolveVerifiedNextSourceBoundary(
            selectedTrackId = 18165L,
            currentTrackId = 18165L,
            selectedQueuePosition = 2,
            currentQueuePosition = 2,
            writerReadback = 2
        )
        assertNotNull(boundary)
        assertEquals(3, boundary!!.nextQueuePosition)

        val refill = TrackMixZeroPoolPolicy.nativeRefillBoundaryPlan(5, 0)
        assertNotNull(refill)
        assertEquals(4, refill?.topUpCount)
    }

'''
test = sub_once(
    test,
    r'''    @Test\n    fun `natural current plus one can bootstrap initial size when natural refill was absent`.*?(?=    @Test\n    fun `native refill boundary tops device observed three followers to initial five`)''',
    new_bootstrap_test,
    'replace R62 bootstrap test',
)

tail_tests = r'''
    @Test
    fun `queue tail fallback accepts only exact zero-provider queue target before next source`() {
        assertTrue(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = true,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_queue",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 3,
                queueSize = 5,
                currentQueuePosition = 4,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
        assertFalse(
            TrackMixZeroPoolPolicy.shouldPrepareQueueTailFallback(
                source = "menu_gm_context_file_browser",
                currentResolutionSource = "queue-context-target",
                preparedTrackCount = 0,
                boundaryPrepared = false,
                boundaryAttempted = false,
                nextSourceObserved = false,
                currentIndex = 4,
                queueSize = 5,
                currentQueuePosition = 5,
                currentTrackId = 4843L,
                selectedTrackId = 4843L
            )
        )
    }
'''
if tail_tests.strip() not in test:
    test = test.rstrip('\n')
    if not test.endswith('}'):
        raise SystemExit('test class closing brace unavailable')
    test = test[:-1].rstrip() + '\n' + tail_tests + '\n}\n'
test_path.write_text(test.rstrip('\n') + '\n')

agents_path = Path('AGENTS.md')
agents = agents_path.read_text()
r69 = re.search(r'^- R69 evidence supersedes R68.*$', agents, flags=re.M)
if not r69:
    raise SystemExit('AGENTS R69 anchor missing')
r70_line = '- R70 device evidence corrects the R62/R64 next-source coordinate assumption. On GMMP 4.2.1 the naturally invoked `MusicService.I2(int)` remains the accepted passive next-source event, but its int argument is opaque: a selected Current at `queue_position=2` still invoked `I2(2)` and the original method immediately queried `queue_position=3`. Never derive queue position from that argument. The tested method alias/signature may identify the event only; mutation authority requires the exact unique pre-Play target row plus independently verified Current writer/readback at one absolute position, and the guarded follower is derived as verified Current+1. If an exact Queue-context Play selects the last native row, has zero prepared matches, no zero-pool boundary was attempted, and no accepted next-source event has yet occurred, the existing position-1 native zero-pool fill may run immediately after that exact Queue target is verified; this closes the tail case where no follower exists to trigger a predecoder callback. No playback method is replayed, prepared-match behavior is unchanged, and any identity/coordinate disagreement still fails closed. Device re-acceptance is required.\n'
if '- R70 device evidence corrects the R62/R64 next-source coordinate assumption.' not in agents:
    insert_at = r69.end()
    agents = agents[:insert_at] + '\n' + r70_line.rstrip('\n') + agents[insert_at:]
agents_path.write_text(agents.rstrip('\n') + '\n')

playbook_path = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
playbook = playbook_path.read_text().rstrip('\n')
section = r'''

### 2026-10-17 R70 opaque I2 argument + Queue-tail zero-pool boundary

The first post-R69 file-browser failure finally disproves the remaining R62/R64 coordinate assumption without disproving the callback itself. The selected Track (`18165`) was independently Current at `queue_position=2`. GMMP then naturally invoked `MusicService.I2(2)`, but inside that same original call immediately queried `queue_position=3` and materialized that row as the next AudioSource. Therefore `I2(int)` remains the repeatedly correlated 4.2.1 next-source materialization event, while its integer argument is not queue position; the older `argument == Current+1` proof worked at Current=1 only by coincidence.

R70 keeps the callback passive and fail-closed. The accepted 4.2.1 `MusicService.I2(int):void` alias/signature may identify the natural event, but the argument grants no coordinate authority. Before the original call proceeds, GoneSmart must uniquely locate the pre-Play-selected Track, read the independently verified Current-position writer, and prove that selected row, Current row and writer readback all agree on one positive absolute `queue_position`. The existing anchored zero-pool native fill then preserves that Current coordinate, and the first guarded follower position is derived as verified `Current+1`. No MusicService method is replayed.

The same device pass exposed a separate Queue-tail case. A Queue click selected exact Track `4843` at position 5 of a five-row queue with zero prepared matches; no natural `qr.z` and no accepted next-source callback occurred before the worker verified the exact Queue target, so the request failed even though GMMP requested Auto-DJ only later. R70 permits the existing position-1 zero-pool native fill at that point only when the source is `menu_gm_context_queue`, the selection proof is `queue-context-target`, the selected row is the final queue row, no zero-pool boundary has been attempted/prepared, and no accepted next-source event has been observed. Because no follower exists in that exact state, this remains before any follower AudioSource can be materialized. Prepared-match starts and all non-tail/non-Queue cases are unchanged. Device re-acceptance remains required.
'''
if '### 2026-10-17 R70 opaque I2 argument + Queue-tail zero-pool boundary' not in playbook:
    playbook += section
playbook_path.write_text(playbook.rstrip('\n') + '\n')
