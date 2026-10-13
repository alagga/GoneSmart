from pathlib import Path
import re

ROOT = Path('.')


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected one exact match, got {count}')
    write(path, text.replace(old, new, 1))


def regex_replace_once(path, pattern, replacement):
    text = read(path)
    new, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{path}: expected one regex match, got {count}')
    write(path, new)


# ---------------------------------------------------------------------------
# 1) Prepared-pool continuity: once Track Mix has pinned CURRENT by queue_id,
# all later native bootstrap refill calls reuse that exact identity hint.
# ---------------------------------------------------------------------------
controller = 'app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt'
replace_once(
    controller,
    '''    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },\n    private val nativeTrackMixPositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },''',
    '''    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },\n    private val nativeTrackMixPositionWriterProvider: (Any, Int) -> NativeQueuePositionWriter? =\n        { _, _ -> null },'''
)
replace_once(
    controller,
    '''        @Volatile var prePlayInitialSize = 0\n        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()''',
    '''        @Volatile var prePlayInitialSize = 0\n        @Volatile var verifiedCurrentQueueEntryId: Long? = null\n        @Volatile var bootstrapAppliedTrackIds: List<Long> = emptyList()'''
)
replace_once(
    controller,
    '''        val currentId = runCatching {\n            queueReader.read(\n                autoDj,\n                nativePositionWriterProvider(autoDj)?.read()\n            )?.currentItem?.track?.id\n        }.getOrNull()\n        if (currentId != target.id) {\n            Log.i(\n                TAG,\n                "MIX PREPLAY BOOTSTRAP SKIP | current=$currentId | target=${target.id} | " +\n                    "requested=$requested"\n            )''',
    '''        val pinnedEntryId = request.verifiedCurrentQueueEntryId\n        val currentId = runCatching {\n            queueReader.read(\n                autoDj,\n                nativePositionWriterProvider(autoDj)?.read(),\n                pinnedEntryId\n            )?.currentItem?.track?.id\n        }.getOrNull()\n        if (currentId != target.id) {\n            Log.i(\n                TAG,\n                "MIX PREPLAY BOOTSTRAP SKIP | current=$currentId | target=${target.id} | " +\n                    "requested=$requested | pinnedEntry=" + (pinnedEntryId ?: -1L)\n            )'''
)
replace_once(
    controller,
    '''            Log.i(\n                TAG,\n                "MIX CURRENT PIN | entry=$selectedEntryId | track=$selectedId | verified=true"\n            )\n\n            val nativeSettings = settings.read()''',
    '''            request.verifiedCurrentQueueEntryId = selectedEntryId\n            Log.i(\n                TAG,\n                "MIX CURRENT PIN | entry=$selectedEntryId | track=$selectedId | verified=true"\n            )\n            val selectedQueuePosition = loaded.currentQueuePosition ?: run {\n                Log.e(TAG, "MIX CURRENT PIN | selected queue_position unavailable")\n                fail("Could not identify the selected queue position safely.", request.context)\n                return\n            }\n\n            val nativeSettings = settings.read()'''
)
replace_once(
    controller,
    '''                        selectedId = selectedId,\n                        selectedEntryId = selectedEntryId,\n                        initial = initial,''',
    '''                        selectedId = selectedId,\n                        selectedEntryId = selectedEntryId,\n                        selectedQueuePosition = selectedQueuePosition,\n                        initial = initial,'''
)
replace_once(
    controller,
    '''        selectedId: Long,\n        selectedEntryId: Long,\n        initial: Int,''',
    '''        selectedId: Long,\n        selectedEntryId: Long,\n        selectedQueuePosition: Int,\n        initial: Int,'''
)
replace_once(
    controller,
    '''        val rebaseWriter = nativeTrackMixPositionWriterProvider(autoDj)''',
    '''        val rebaseWriter = nativeTrackMixPositionWriterProvider(\n            autoDj,\n            selectedQueuePosition\n        )'''
)
replace_once(
    controller,
    '''        val finalWriter = nativePositionWriterProvider(autoDj)\n            ?: nativeTrackMixPositionWriterProvider(autoDj)\n            ?: return null''',
    '''        val finalWriter = nativePositionWriterProvider(autoDj)\n            ?: rebaseWriter'''
)

# ---------------------------------------------------------------------------
# 2) r36 proved the Track-Mix-only native State writer uses a coordinate that
# can be zero-based while queue_table.queue_position is one-based. Infer both
# method-argument and read-signal offsets only from a naturally observed state
# method plus the later exact pinned CURRENT. Never promote it globally.
# ---------------------------------------------------------------------------
policy = 'app/src/main/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicy.kt'
replace_once(
    policy,
    '''    fun acceptPassivePositionWriter(\n        isStateWriter: Boolean,\n        before: Int?,\n        after: Int?,\n        argument: Int,\n        uniqueQueuePosition: Boolean\n    ): Boolean =\n        isStateWriter &&\n            uniqueQueuePosition &&\n            before != null &&\n            after != null &&\n            before == argument &&\n            after == argument\n''',
    '''    data class StateWriterMapping(\n        val argumentOffset: Int,\n        val signalOffset: Int\n    )\n\n    fun resolveStateWriterMapping(\n        currentQueuePosition: Int,\n        observedArgument: Int,\n        signalValue: Int,\n        ageMs: Long,\n        maxAgeMs: Long = 10_000L\n    ): StateWriterMapping? {\n        if (currentQueuePosition <= 0 || ageMs !in 0..maxAgeMs) return null\n        val argumentOffset = currentQueuePosition - observedArgument\n        val signalOffset = currentQueuePosition - signalValue\n        if (argumentOffset !in 0..1 || signalOffset !in 0..1) return null\n        return StateWriterMapping(\n            argumentOffset = argumentOffset,\n            signalOffset = signalOffset\n        )\n    }\n'''
)

observer = 'app/src/main/java/io/github/alagga/gonesmart/NativeQueuePositionWriterObserver.kt'
replace_once(
    observer,
    '''    private data class PassiveSameValue(\n        val receiver: WeakReference<Any>,\n        val autoDj: WeakReference<Any>,\n        val method: Method,\n        val looper: Looper?,\n        val signalSource: String\n    )\n\n    private val controlledWrite = ThreadLocal<Boolean>()\n\n    @Volatile\n    private var passiveSameValue: PassiveSameValue? = null\n\n    @Volatile\n    private var passiveSameValueAmbiguous = false''',
    '''    private data class PassiveTrackMixCandidate(\n        val receiver: WeakReference<Any>,\n        val autoDj: WeakReference<Any>,\n        val method: Method,\n        val looper: Looper?,\n        val lastArgument: Int,\n        val observedAt: Long\n    )\n\n    private val controlledWrite = ThreadLocal<Boolean>()\n\n    @Volatile\n    private var passiveTrackMixCandidate: PassiveTrackMixCandidate? = null\n\n    @Volatile\n    private var passiveTrackMixAmbiguous = false'''
)
replace_once(
    observer,
    '''        val sameValueUnique =\n            stateWriter &&\n                before != null &&\n                after != null &&\n                before.value == argument &&\n                after.value == argument &&\n                isUniqueQueuePosition(autoDj, argument)\n        if (TrackMixZeroPoolPolicy.acceptPassivePositionWriter(\n                isStateWriter = stateWriter,\n                before = before?.value,\n                after = after?.value,\n                argument = argument,\n                uniqueQueuePosition = sameValueUnique\n            )\n        ) {\n            recordPassiveSameValue(\n                receiver = service,\n                autoDj = autoDj,\n                method = method,\n                looper = invocationLooper,\n                signalSource = after?.source ?: before?.source ?: "native-signal"\n            )\n        }\n\n        if (!stateWriter) return result''',
    '''        if (stateWriter) {\n            recordPassiveTrackMixCandidate(\n                receiver = service,\n                autoDj = autoDj,\n                method = method,\n                looper = invocationLooper,\n                argument = argument,\n                observedAt = SystemClock.elapsedRealtime()\n            )\n        }\n\n        if (!stateWriter) return result'''
)
regex_replace_once(
    observer,
    r'''    /\*\*\n     \* Track Mix may need to move the exact CURRENT row.*?\n    private fun invokeControlled\(''',
    '''    /**\n     * Track Mix may need to move the exact CURRENT row from a Smart-source\n     * absolute queue_position to 1. r36 device evidence showed the naturally\n     * observed state method may use a zero-based coordinate (for example a\n     * natural argument 4 while GMMP owns queue_position 5). Keep this mapping\n     * request-local: correlate the latest naturally observed structural state\n     * writer and native read signal with the already pinned CURRENT, accepting\n     * only exact offsets 0 or 1. Never promote this weaker mapping to Queue\n     * Flip's global writer proof.\n     */\n    fun trackMixBinding(\n        autoDj: Any,\n        currentQueuePosition: Int\n    ): NativeQueuePositionWriter? {\n        binding(autoDj)?.let { absolute ->\n            if (runCatching { absolute.read() }.getOrNull() == currentQueuePosition) {\n                return absolute\n            }\n        }\n        if (passiveTrackMixAmbiguous) return null\n        val candidate = passiveTrackMixCandidate ?: return null\n        if (candidate.autoDj.get() !== autoDj) return null\n        val receiver = candidate.receiver.get() ?: return null\n        val reading = readSignal(autoDj) ?: return null\n        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(\n            currentQueuePosition = currentQueuePosition,\n            observedArgument = candidate.lastArgument,\n            signalValue = reading.value,\n            ageMs = SystemClock.elapsedRealtime() - candidate.observedAt\n        ) ?: return null\n        val provisional = Verified(\n            receiver = candidate.receiver,\n            autoDj = candidate.autoDj,\n            method = candidate.method,\n            looper = candidate.looper,\n            signalSource = reading.source,\n            sameHostReader = null\n        )\n\n        Log.i(\n            TAG,\n            "QUEUE POSITION TRACK MIX MAPPING | writer=" +\n                candidate.method.declaringClass.name + "." + candidate.method.name +\n                "(int) | queuePosition=" + currentQueuePosition +\n                " | naturalArg=" + candidate.lastArgument +\n                " | signal=" + reading.value +\n                " | argumentOffset=" + mapping.argumentOffset +\n                " | signalOffset=" + mapping.signalOffset +\n                " | requestScoped=true"\n        )\n\n        return object : NativeQueuePositionWriter {\n            override val description: String =\n                candidate.method.declaringClass.name + "." + candidate.method.name +\n                    "(int) via track-mix-offset(arg=" + mapping.argumentOffset +\n                    ",signal=" + mapping.signalOffset + ")"\n\n            override fun read(): Int? =\n                readSignal(autoDj)?.value?.let { nativeValue ->\n                    nativeValue + mapping.signalOffset\n                }?.takeIf { it > 0 }\n\n            override fun write(position: Int): Boolean {\n                if (position <= 0) return false\n                val nativeArgument = position - mapping.argumentOffset\n                if (nativeArgument < 0) return false\n                if (!invokeControlled(\n                        proof = provisional,\n                        receiver = receiver,\n                        autoDj = autoDj,\n                        position = nativeArgument\n                    )\n                ) return false\n\n                val deadline = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS\n                do {\n                    val mappedPosition = read()\n                    if (mappedPosition == position &&\n                        isUniqueQueuePosition(autoDj, position)\n                    ) {\n                        Log.i(\n                            TAG,\n                            "QUEUE POSITION TRACK MIX WRITE VERIFIED | writer=" +\n                                candidate.method.declaringClass.name + "." +\n                                candidate.method.name +\n                                " | queuePosition=" + position +\n                                " | nativeArgument=" + nativeArgument +\n                                " | requestScoped=true"\n                        )\n                        return true\n                    }\n                    Thread.sleep(20L)\n                } while (SystemClock.elapsedRealtime() < deadline)\n                return false\n            }\n\n            override fun refreshCurrent(position: Int): Boolean = false\n        }\n    }\n\n    @Synchronized\n    private fun recordPassiveTrackMixCandidate(\n        receiver: Any,\n        autoDj: Any,\n        method: Method,\n        looper: Looper?,\n        argument: Int,\n        observedAt: Long\n    ) {\n        val existing = passiveTrackMixCandidate\n        if (existing != null && existing.autoDj.get() !== autoDj) {\n            passiveTrackMixCandidate = null\n            passiveTrackMixAmbiguous = false\n        }\n        val current = passiveTrackMixCandidate\n        if (current == null) {\n            method.isAccessible = true\n            passiveTrackMixCandidate = PassiveTrackMixCandidate(\n                receiver = WeakReference(receiver),\n                autoDj = WeakReference(autoDj),\n                method = method,\n                looper = looper,\n                lastArgument = argument,\n                observedAt = observedAt\n            )\n            Log.i(\n                TAG,\n                "QUEUE POSITION TRACK MIX CANDIDATE | writer=" +\n                    method.declaringClass.name + "." + method.name +\n                    "(int) | natural=true"\n            )\n            return\n        }\n        if (current.receiver.get() === receiver &&\n            current.autoDj.get() === autoDj &&\n            sameMethod(current.method, method)\n        ) {\n            passiveTrackMixCandidate = current.copy(\n                looper = looper,\n                lastArgument = argument,\n                observedAt = observedAt\n            )\n            return\n        }\n\n        passiveTrackMixAmbiguous = true\n        Log.w(\n            TAG,\n            "QUEUE POSITION TRACK MIX AMBIGUOUS | zero-pool rebase disabled"\n        )\n    }\n\n    private fun invokeControlled('''
)

# QueueFlip exposes the request-scoped mapping only to Track Mix.
queue_flip = 'app/src/main/java/io/github/alagga/gonesmart/QueueFlipController.kt'
replace_once(
    queue_flip,
    '''    fun trackMixPositionWriter(autoDj: Any): NativeQueuePositionWriter? =\n        positionWriterObserver.trackMixBinding(autoDj)''',
    '''    fun trackMixPositionWriter(\n        autoDj: Any,\n        currentQueuePosition: Int\n    ): NativeQueuePositionWriter? =\n        positionWriterObserver.trackMixBinding(autoDj, currentQueuePosition)'''
)

module = 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
replace_once(
    module,
    '''        nativeTrackMixPositionWriterProvider = { autoDj ->\n            queueFlipController.trackMixPositionWriter(autoDj)\n        },''',
    '''        nativeTrackMixPositionWriterProvider = { autoDj, currentQueuePosition ->\n            queueFlipController.trackMixPositionWriter(\n                autoDj,\n                currentQueuePosition\n            )\n        },'''
)
replace_once(
    module,
    '''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r36"''',
    '''        private const val COMPAT_PROBE_REVISION =\n            "gmmp421-r37"'''
)

# ---------------------------------------------------------------------------
# 3) Pure-policy tests for the bounded zero/one offset mapping.
# ---------------------------------------------------------------------------
test = 'app/src/test/java/io/github/alagga/gonesmart/TrackMixZeroPoolPolicyTest.kt'
text = read(test)
text = text.replace('import org.junit.Assert.assertTrue\nimport org.junit.Assert.assertFalse\n', 'import org.junit.Assert.assertNotNull\n')
old_test = '''    @Test\n    fun `passive same value writer candidate needs structural ownership and unique row`() {\n        assertTrue(\n            TrackMixZeroPoolPolicy.acceptPassivePositionWriter(\n                isStateWriter = true,\n                before = 15,\n                after = 15,\n                argument = 15,\n                uniqueQueuePosition = true\n            )\n        )\n        assertFalse(\n            TrackMixZeroPoolPolicy.acceptPassivePositionWriter(\n                isStateWriter = false,\n                before = 15,\n                after = 15,\n                argument = 15,\n                uniqueQueuePosition = true\n            )\n        )\n        assertFalse(\n            TrackMixZeroPoolPolicy.acceptPassivePositionWriter(\n                isStateWriter = true,\n                before = 15,\n                after = 15,\n                argument = 15,\n                uniqueQueuePosition = false\n            )\n        )\n    }\n'''
new_test = '''    @Test\n    fun `track mix state writer maps zero based argument and signal to queue position`() {\n        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(\n            currentQueuePosition = 5,\n            observedArgument = 4,\n            signalValue = 4,\n            ageMs = 500\n        )\n        assertNotNull(mapping)\n        assertEquals(1, mapping!!.argumentOffset)\n        assertEquals(1, mapping.signalOffset)\n    }\n\n    @Test\n    fun `track mix state writer allows mixed absolute signal and zero based argument`() {\n        val mapping = TrackMixZeroPoolPolicy.resolveStateWriterMapping(\n            currentQueuePosition = 5,\n            observedArgument = 4,\n            signalValue = 5,\n            ageMs = 500\n        )\n        assertNotNull(mapping)\n        assertEquals(1, mapping!!.argumentOffset)\n        assertEquals(0, mapping.signalOffset)\n    }\n\n    @Test\n    fun `track mix state writer refuses stale or wider coordinate guesses`() {\n        assertNull(\n            TrackMixZeroPoolPolicy.resolveStateWriterMapping(\n                currentQueuePosition = 5,\n                observedArgument = 3,\n                signalValue = 4,\n                ageMs = 500\n            )\n        )\n        assertNull(\n            TrackMixZeroPoolPolicy.resolveStateWriterMapping(\n                currentQueuePosition = 5,\n                observedArgument = 4,\n                signalValue = 4,\n                ageMs = 10_001\n            )\n        )\n    }\n'''
if old_test not in text:
    raise SystemExit('TrackMixZeroPoolPolicyTest: old passive test not found')
write(test, text.replace(old_test, new_test, 1))

# ---------------------------------------------------------------------------
# 4) Durable docs: record the device-proven r36 failures and r37 invariants.
# ---------------------------------------------------------------------------
agents = 'AGENTS.md'
replace_once(
    agents,
    '''- Prepared GoneSmart Track IDs occupy the first upcoming slots; existing native Track IDs remain only for a genuine prepared-pool shortfall. Do not perform a second Initial-Size refill after recommendations are ready.\n''',
    '''- Prepared GoneSmart Track IDs occupy the first upcoming slots; existing native Track IDs remain only for a genuine prepared-pool shortfall. Once Track Mix has independently pinned CURRENT by native `queue_id`, every later prepared-pool bootstrap refill must carry that exact entry as the CURRENT hint instead of rediscovering CURRENT from a transient position reader. Do not perform a second Initial-Size refill after recommendations are ready.\n- A Track-Mix-only Current rebase boundary may use a native zero-based state coordinate while `queue_table.queue_position` is one-based. Infer a request-scoped 0/1 argument/read offset only from a naturally observed structural state-writer call plus the later exact pinned CURRENT; ambiguity, stale evidence or a wider offset fails closed. Never generalize that weaker mapping into Queue Flip's global position-writer proof.\n'''
)

append = '''\n\n## GMMP 4.2.1 r37 Track Mix bootstrap/current-coordinate repair\n- r36 device evidence proved the Levels recommendation pipeline itself remained healthy: ListenBrainz and Last.fm produced 125 merged candidates and 20 accepted exact local matches, but only the first prepared Track ID materialized. After `MIX CURRENT PIN`, a second native refill re-read CURRENT without the pinned queue entry, got `current=null`, skipped the remaining prepared IDs and fell back to GMMP's random native Auto-DJ pool. r37 stores the verified `queue_id` on the pending request and supplies it to every later prepared bootstrap read.\n- The same r36 pass proved the zero-provider BUNT. path reached `MIX CURRENT PIN` but failed before mutation because the Track-Mix Current writer was assumed to share `queue_position` coordinates. Natural device evidence showed the structural state call with argument 4 while GMMP owned queue position 5. r37 therefore treats the Track-Mix-only state writer as request-scoped and infers only offset 0 or 1 between the natural method argument/read signal and the exact pinned queue position. Wider, stale or ambiguous mappings fail closed.\n- The r37 mapping never promotes the weaker Track-Mix state candidate into Queue Flip's global writer proof. `CLEAR_QUEUE`, post-materialization Track-ID retarget and same-position CURRENT refresh remain forbidden.\n'''
for doc in ['docs/GMMP_COMPATIBILITY_PLAYBOOK.md', 'docs/GMMP_421_COMPLETION.md']:
    text = read(doc)
    if '## GMMP 4.2.1 r37 Track Mix bootstrap/current-coordinate repair' not in text:
        write(doc, text.rstrip() + append + '\n')

check = 'docs/GMMP_421_REACCEPTANCE_CHECKLIST.md'
text = read(check)
text = text.replace(
    'current **r36** Playlist Link / Track Mix repair candidate',
    'current **r37** Playlist Link / Track Mix repair candidate',
    1
)
text = re.sub(
    r'Repair source commit: `[^`]+`\.',
    'Repair source commit: `R37_REPAIR_SOURCE_PENDING`.',
    text,
    count=1
)
text = text.replace('revision `gmmp421-r36`', 'revision `gmmp421-r37`', 1)
r37 = '''\n\n## r37 regression focus\n- Re-run Avicii `Levels (Radio Edit)`. The provider/local-matching stage should still report a populated prepared pool (the r36 device run produced 20 accepted local matches and `prepared=4`). After `MIX CURRENT PIN`, every remaining bootstrap refill must resolve CURRENT through the pinned native queue entry rather than log `MIX PREPLAY BOOTSTRAP SKIP | current=null`. All four prepared IDs must materialize before prune; no `SMART DJ NATIVE FALLBACK` may replace the remaining prepared slots with GMMP random tracks.\n- Re-run zero-provider BUNT. The accepted r35 exact-path rebind and r36 next-source boundary must still occur. The Track-Mix-only Current writer should log a request-scoped mapping whose argument/signal offsets are each 0 or 1, then the zero-pool seed must rebase the exact pinned BUNT. entry to queue position 1, refill four native Auto-DJ rows, compact 1..5 and refresh native position 2.\n- BUNT. must end at **1 of 5** and naturally transition into the newly generated Track 2. Levels must keep the four GoneSmart recommendation slots and naturally decode Track 2.\n- Safety invariants remain: no `CLEAR_QUEUE`, no Track-ID rewrite after playback materialization, no same-position CURRENT refresh, no hard-coded `dx3.c2`/`MusicService.I2`, and stale/ambiguous coordinate evidence fails closed.\n'''
if '## r37 regression focus' not in text:
    text = text.rstrip() + r37 + '\n'
write(check, text)

print('r37 repair applied')
