from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


# 1) Playlist Link: one uninspectable Dex class must never abort semantic
# discovery. Android can throw NoClassDefFoundError from getDeclaredFields()
# when a GMMP class references a newer framework API than this device has.
resolver = 'app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt'
replace_once(
    resolver,
    '''    fun fields(type: Class<*>): List<Field> {
        val out = linkedMapOf<String, Field>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.forEach { field ->
                out.putIfAbsent(current.name + "#" + field.name, field)
            }
            current = current.superclass
        }
        return out.values.toList()
    }
''',
    '''    internal fun declaredFieldsSafely(
        type: Class<*>,
        reader: (Class<*>) -> Array<Field> = { it.declaredFields }
    ): List<Field> = try {
        reader(type).toList()
    } catch (_: LinkageError) {
        emptyList()
    } catch (_: TypeNotPresentException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    fun fields(type: Class<*>): List<Field> {
        val out = linkedMapOf<String, Field>()
        var current: Class<*>? = type
        while (current != null) {
            val declaring = current
            declaredFieldsSafely(declaring).forEach { field ->
                out.putIfAbsent(declaring.name + "#" + field.name, field)
            }
            current = runCatching { declaring.superclass }.getOrNull()
        }
        return out.values.toList()
    }
'''
)
replace_once(
    resolver,
    '''                type.declaredFields
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
''',
    '''                declaredFieldsSafely(type)
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
'''
)
replace_once(
    resolver,
    '''    private fun potentialSemanticHolder(
        type: Class<*>,
        valueClass: Class<*>
    ): Boolean = type.declaredFields.any { field ->
        java.lang.reflect.Modifier.isStatic(field.modifiers) &&
            !field.type.isPrimitive &&
            field.type != Any::class.java &&
            (
                field.type.isAssignableFrom(valueClass) ||
                    valueClass.isAssignableFrom(field.type)
                )
    }
''',
    '''    private fun potentialSemanticHolder(
        type: Class<*>,
        valueClass: Class<*>
    ): Boolean = declaredFieldsSafely(type).any { field ->
        runCatching {
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                !field.type.isPrimitive &&
                field.type != Any::class.java &&
                (
                    field.type.isAssignableFrom(valueClass) ||
                        valueClass.isAssignableFrom(field.type)
                    )
        }.getOrDefault(false)
    }
'''
)

resolver_test = 'app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt'
replace_once(
    resolver_test,
    '''import org.junit.Assert.assertSame
import org.junit.Test
''',
    '''import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
'''
)
replace_once(
    resolver_test,
    '''    @Test
    fun semanticFieldDiscoveryFallsBackBeyondHistoricalHolderNames() {
''',
    '''    @Test
    fun unavailableAndroidFieldMetadataIsSkippedInsteadOfAbortingDiscovery() {
        val fields = PlaylistBridgeReflectionResolver.declaredFieldsSafely(
            QueryFieldsFixture::class.java
        ) {
            throw NoClassDefFoundError("android/view/ScrollFeedbackProvider")
        }
        assertTrue(fields.isEmpty())
    }

    @Test
    fun semanticFieldDiscoveryFallsBackBeyondHistoricalHolderNames() {
'''
)

# 2) Track Mix: model refill ownership explicitly. On 4.2.1 WAIT_PLAY is still
# GMMP-owned playback continuity, so its refill must pass through natively.
# Only CLEARING/FILLING are GoneSmart-owned mutation boundaries. Preserve the
# accepted 4.2.0/ex3 hold behavior unchanged.
fill_policy = 'app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt'
replace_once(
    fill_policy,
    '''internal object TrackMixInitialFillPolicy {
    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
''',
    '''internal object TrackMixInitialFillPolicy {
    enum class NativeRefillAction {
        NORMAL,
        PASS_NATIVE,
        SUPPRESS
    }

    private const val LEGACY_COMMAND_REFILL_WAIT_MS = 1_800L
'''
)
replace_once(
    fill_policy,
    '''    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    /**
''',
    '''    fun autoDjCommandBoundaryWaitMs(legacyQueue: Boolean): Long =
        if (legacyQueue) LEGACY_COMMAND_REFILL_WAIT_MS
        else GMMP_421_COMMAND_PREPARE_WAIT_MS

    fun nativeRefillAction(
        holdActive: Boolean,
        explicitAllowance: Boolean,
        stage: String?,
        legacyQueue: Boolean
    ): NativeRefillAction {
        if (!holdActive || explicitAllowance) return NativeRefillAction.NORMAL
        if (legacyQueue) return NativeRefillAction.SUPPRESS
        return when (stage) {
            "WAIT_PLAY" -> NativeRefillAction.PASS_NATIVE
            "CLEARING", "FILLING" -> NativeRefillAction.SUPPRESS
            else -> NativeRefillAction.NORMAL
        }
    }

    /**
'''
)

track_mix = 'app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt'
replace_once(
    track_mix,
    '''    fun shouldSuppressNativeRefill(): Boolean {
        if ((nativeRefillAllowance.get() ?: 0) > 0) return false
        if (!refillHold.get()) return false
        val request = pending
        if (request?.stage == "FILLING") {
            request.transitionalRefillSuppressed = true
        }
        Log.i(
            TAG,
            "MIX AUTO-DJ HOLD | stage=${request?.stage ?: "pre-play"} | " +
                "deferring native refill until Track Mix initial fill"
        )
        return true
    }
''',
    '''    fun nativeRefillAction(): TrackMixInitialFillPolicy.NativeRefillAction {
        val request = pending
        val explicitAllowance = (nativeRefillAllowance.get() ?: 0) > 0
        val legacyQueue = nativeAutoDj?.get()?.let { autoDj ->
            field(autoDj, "q")?.javaClass?.name == "ex3"
        } == true || nativeQueue?.get()?.javaClass?.name == "ex3"
        val action = TrackMixInitialFillPolicy.nativeRefillAction(
            holdActive = refillHold.get(),
            explicitAllowance = explicitAllowance,
            stage = request?.stage,
            legacyQueue = legacyQueue
        )
        if (action == TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS) {
            if (request?.stage == "FILLING") {
                request.transitionalRefillSuppressed = true
            }
            Log.i(
                TAG,
                "MIX AUTO-DJ HOLD | stage=${request?.stage ?: "pre-play"} | " +
                    "legacyQueue=$legacyQueue | deferring native refill"
            )
        }
        return action
    }
'''
)
replace_once(
    track_mix,
    '''                val expectedPositions = (1..ordered.size).toList()
                val actualPositions = ordered.map { it.queuePosition }
                if (actualPositions != expectedPositions) {
''',
    '''                val actualPositions = ordered.map { it.queuePosition }
                val expectedPositions = actualPositions.firstOrNull()?.let { first ->
                    List(ordered.size) { offset -> first + offset }
                } ?: emptyList()
                if (actualPositions != expectedPositions) {
'''
)

module = 'app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt'
replace_once(
    module,
    '''                if (trackMixController.shouldSuppressNativeRefill()) {
                    // GMMP may start a refill as soon as native Play
                    // seeks to the selected queue row. That older refill
                    // races CLEAR_QUEUE and caused intermittent failures.
                    // Track Mix will explicitly enable native Auto-DJ
                    // after it confirms that only the new seed remains.
                    return@intercept null
                }
                trackMixController.onNativeAutoDjRefillRequested(
                    requestedTracks
                )
''',
    '''                when (trackMixController.nativeRefillAction()) {
                    TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE -> {
                        // Before 4.2.1 seed isolation, GMMP still owns playback
                        // continuity. Do not route this natural refill through
                        // GoneSmart recommendation preparation and do not hold it:
                        // GMMP can query next/current+1 within tens of ms.
                        Log.i(
                            "GoneSmartTrackMix",
                            "MIX AUTO-DJ CONTINUITY | stage=WAIT_PLAY | " +
                                "native refill pass-through | requested=$requestedTracks"
                        )
                        return@intercept chain.proceed()
                    }
                    TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS -> {
                        return@intercept null
                    }
                    TrackMixInitialFillPolicy.NativeRefillAction.NORMAL -> {
                        trackMixController.onNativeAutoDjRefillRequested(
                            requestedTracks
                        )
                    }
                }
'''
)

# 3) Track Mix seed isolation: the selected song is already naturally CURRENT.
# Delete stale rows but preserve its absolute queue_position and current pointer.
# No additional current writer is needed at this fragile boundary.
bridge = 'app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt'
replace_once(
    bridge,
    '''        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        }
        val selectedMatches = resolved.context.items.filter {
            it.track.id == selectedTrackId
        }
        val selected = when {
            current?.track?.id == selectedTrackId -> current
            selectedMatches.size == 1 -> selectedMatches.single()
            else -> return false
        }
        if (resolved.rows.size == 1) {
            return current?.track?.id == selectedTrackId
        }

        val pointerAlreadyTargetsOne =
            resolved.context.currentQueuePosition == 1
        val statePosition = if (pointerAlreadyTargetsOne) {
            null
        } else {
            resolveStatePosition(resolved.context.currentQueuePosition)
        }

        val selectedModel = resolved.rows.singleOrNull {
            number(resolved.queueId, it).toLong() == selected.queueEntryId
        } ?: return false
''',
    '''        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: return false
        if (current.track.id != selectedTrackId) return false
        val selected = current
        if (resolved.rows.size == 1) return true

        val selectedModel = resolved.rows.singleOrNull {
            number(resolved.queueId, it).toLong() == selected.queueEntryId
        } ?: return false
'''
)
replace_once(
    bridge,
    '''        delete.invoke(resolved.dao, staleArray)
        setInt(resolved.position, selectedModel, 1)
        resolved.update.invoke(
            resolved.dao,
            arrayListOf(selectedModel)
        )
        statePosition?.write(1)

        val verified = GmmpQueueReader().read(
            autoDj,
            statePosition?.read()
        ) ?: return false
        val only = verified.items.singleOrNull() ?: return false
        val ok = only.track.id == selectedTrackId &&
            only.queueEntryId == selected.queueEntryId &&
            only.state == QueueItemState.CURRENT
        if (ok) {
            Log.i(
                TAG,
                "QUEUE MUTATION | seed isolation verified | track=" +
                    selectedTrackId + " | queueId=" + only.queueEntryId +
                    " | realigned=" + (selected.state != QueueItemState.CURRENT)
            )
        }
        return ok
''',
    '''        delete.invoke(resolved.dao, staleArray)

        val verified = GmmpQueueReader().read(
            autoDj,
            resolved.context.currentQueuePosition
        ) ?: return false
        val only = verified.items.singleOrNull() ?: return false
        val ok = only.track.id == selectedTrackId &&
            only.queueEntryId == selected.queueEntryId &&
            only.queuePosition == selected.queuePosition &&
            only.state == QueueItemState.CURRENT
        if (ok) {
            Log.i(
                TAG,
                "QUEUE MUTATION | seed isolation verified | track=" +
                    selectedTrackId + " | queueId=" + only.queueEntryId +
                    " | positionPreserved=" + only.queuePosition
            )
        }
        return ok
'''
)

# Normalize only gaps around the already verified current absolute position.
# Never move Current just to make a Track-Mix-owned queue start at 1.
normalization = 'app/src/main/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicy.kt'
replace_once(
    normalization,
    ''' * Instead, after a native refill, a small Track-Auto-DJ-owned queue may be
 * rebased through the already-proven native Queue DAO update writer.
''',
    ''' * Instead, after a native refill, a small Track-Auto-DJ-owned queue may be
 * compacted through the already-proven native Queue DAO update writer while
 * preserving the naturally verified absolute Current position.
'''
)
replace_once(
    normalization,
    '''        val original = ordered.map { it.position }
        val normalized = List(ordered.size) { it + 1 }
        if (original == normalized) return null

        return Plan(
            orderedQueueIds = ordered.map { it.queueId },
            originalPositions = original,
            normalizedPositions = normalized,
            currentQueueId = currentQueueId,
            currentNewPosition = currentIndex + 1
        )
''',
    '''        val original = ordered.map { it.position }
        val currentPosition = ordered[currentIndex].position
        val firstNormalizedPosition = currentPosition - currentIndex
        require(firstNormalizedPosition > 0) {
            "Current position cannot anchor a positive contiguous queue"
        }
        val normalized = List(ordered.size) { offset ->
            firstNormalizedPosition + offset
        }
        if (original == normalized) return null

        return Plan(
            orderedQueueIds = ordered.map { it.queueId },
            originalPositions = original,
            normalizedPositions = normalized,
            currentQueueId = currentQueueId,
            currentNewPosition = currentPosition
        )
'''
)

# Tests reproduce the two Track Mix timing/state regressions from the device log.
fill_test = 'app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt'
replace_once(
    fill_test,
    '''    @Test fun isolatedTrackMixSeedNeverWaitsForSmartPool() {
''',
    '''    @Test fun gmmp421WaitPlayRefillPassesThroughButMutationStagesHold() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.PASS_NATIVE,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "WAIT_PLAY",
                legacyQueue = false
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "CLEARING",
                legacyQueue = false
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "FILLING",
                legacyQueue = false
            )
        )
    }

    @Test fun legacyWaitPlayHoldAndExplicit421RefillStayUnchanged() {
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.SUPPRESS,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = false,
                stage = "WAIT_PLAY",
                legacyQueue = true
            )
        )
        assertEquals(
            TrackMixInitialFillPolicy.NativeRefillAction.NORMAL,
            TrackMixInitialFillPolicy.nativeRefillAction(
                holdActive = true,
                explicitAllowance = true,
                stage = "FILLING",
                legacyQueue = false
            )
        )
    }

    @Test fun isolatedTrackMixSeedNeverWaitsForSmartPool() {
'''
)

norm_test = 'app/src/test/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicyTest.kt'
replace_once(
    norm_test,
    '''    fun `current row is moved to its corresponding normalized position`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(20L, 5908),
                QueuePositionNormalizationPolicy.Row(21L, 5912),
                QueuePositionNormalizationPolicy.Row(22L, 5913)
            ),
            currentQueueId = 21L
        )!!

        assertEquals(listOf(1, 2, 3), plan.normalizedPositions)
        assertEquals(2, plan.currentNewPosition)
    }
''',
    '''    fun `normalization preserves the verified absolute current position`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(20L, 5908),
                QueuePositionNormalizationPolicy.Row(21L, 5912),
                QueuePositionNormalizationPolicy.Row(22L, 5913)
            ),
            currentQueueId = 21L
        )!!

        assertEquals(listOf(5911, 5912, 5913), plan.normalizedPositions)
        assertEquals(5912, plan.currentNewPosition)
    }

    @Test
    fun `first Track Mix seed can stay at position five while refill gaps close`() {
        val plan = QueuePositionNormalizationPolicy.plan(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(50L, 5),
                QueuePositionNormalizationPolicy.Row(51L, 11),
                QueuePositionNormalizationPolicy.Row(52L, 12),
                QueuePositionNormalizationPolicy.Row(53L, 13),
                QueuePositionNormalizationPolicy.Row(54L, 14)
            ),
            currentQueueId = 50L
        )!!

        assertEquals(listOf(5, 6, 7, 8, 9), plan.normalizedPositions)
        assertEquals(5, plan.currentNewPosition)
    }
'''
)

# Durable docs: capture the rule, not just this incident.
agents = Path('AGENTS.md')
text = agents.read_text()
needle = '- After explicit seed isolation on 4.2.1, the Initial Size refill must not wait for the remote Smart-DJ recommendation pool at all. Start/keep that preparation asynchronously, let native GMMP populate continuity immediately, and use the resulting pool only for later ordinary refills.\n'
addition = needle + '- Before seed isolation on 4.2.1, `WAIT_PLAY` is still GMMP-owned playback continuity: its natural Auto-DJ refill passes through natively. Only the Track-Mix-owned `CLEARING`/`FILLING` mutation window may suppress a native refill. Seed isolation preserves the naturally verified absolute Current position; later compaction closes queue-position gaps around that Current anchor instead of forcing Current to position 1.\n'
if text.count(needle) != 1:
    raise SystemExit('AGENTS.md: Track Mix rule anchor mismatch')
text = text.replace(needle, addition, 1)
needle2 = '- Resolve Playlist Link query-column constants by native semantic value (for example `track_uri`), with historical field names only as fast paths. If R8 makes a query-field object\'s direct representation opaque, semantic discovery may inspect only a bounded read-only in-memory value graph for that native identifier; it must not invoke unknown methods or execute queries, and uniqueness remains mandatory. If the historical holder classes no longer own the value, enumerate class names from the already loaded target dex without initializing them, inspect only type-compatible static fields, and (when necessary) use the already-resolved pure native predicate builder as an in-memory semantic probe; ambiguity still fails closed. Optional query capabilities must be lazy: an unproven OR-group mapping must not disable a single native-IN link, while a source that actually requires the missing group fails closed.\n'
addition2 = needle2.replace('inspect only type-compatible static fields,', 'inspect only type-compatible static fields whose reflection metadata is resolvable on the current Android runtime (skip individual holders that raise linkage/type-resolution errors),')
if text.count(needle2) != 1:
    raise SystemExit('AGENTS.md: Playlist Link rule anchor mismatch')
text = text.replace(needle2, addition2, 1)
agents.write_text(text)

playbook = Path('docs/GMMP_COMPATIBILITY_PLAYBOOK.md')
text = playbook.read_text()
needle = 'Runtime `3cf7c504dc7d` then proved two remaining boundaries in the same device pass. Playlist Link still failed before semantic value inspection because the historical `w75`/`z75` holder classes themselves no longer matched the expected query-field holder shape. Their names are therefore only fast paths now: the fallback enumerates class names from GMMP\'s already loaded dex without initialization, filters for type-compatible static fields and requires one unique semantic `track_uri`; when the field object is opaque, the already-resolved native `IN` builder may be used only as an in-memory semantic probe and no query is executed. The same run proved Track Mix\'s 90 ms `qr.z(initial-deficit)` boundary was active, but the refill hook then waited another 1.5 s for the remote Smart-DJ pool while GMMP had already queried missing queue position 2 and logged `next audio source is null`. Explicit Track Mix Initial Size refill therefore no longer waits for that pool at all: preparation continues asynchronously and native GMMP fills continuity immediately.\n'
addition = needle + '\nRuntime `4cfe88ea5430` exposed the next bundled state/timing failures without needing another mapping guess. Playlist Link\'s broad fallback reached a GMMP class whose reflected field metadata referenced unavailable `android.view.ScrollFeedbackProvider`; one uninspectable dex class must now be skipped rather than abort the entire semantic search. Track Mix also showed that the remaining `next audio source is null` occurs earlier, during `WAIT_PLAY`, because GoneSmart held GMMP\'s natural refill before playback identity had settled. On 4.2.1 that pre-isolation refill is GMMP-owned continuity and passes through natively; only `CLEARING`/`FILLING` remain held. Finally, the first Track Mix run failed after deleting stale rows because GoneSmart unnecessarily rebased an already naturally Current seed from absolute position 5 to 1 through the passive Current writer. Seed isolation now keeps the verified absolute Current position and later compacts only gaps around that anchor, so Track Mix does not rewrite Current merely to make its queue start at 1. Device re-acceptance is still pending.\n'
if text.count(needle) != 1:
    raise SystemExit('playbook: chronology anchor mismatch')
playbook.write_text(text.replace(needle, addition, 1))

completion = Path('docs/GMMP_421_COMPLETION.md')
text = completion.read_text().rstrip() + '\n- 9 October 2026: runtime `4cfe88ea5430` proved three remaining defects in one bundled trace: Playlist Link semantic dex discovery aborted on an unrelated holder whose field metadata linked unavailable `android.view.ScrollFeedbackProvider`; Track Mix suppressed GMMP\'s natural 4.2.1 refill during `WAIT_PLAY` and GMMP then queried the missing next position twice; and the first seed isolation deleted stale rows successfully but failed only when GoneSmart additionally tried to move an already naturally Current seed from absolute position 5 to 1. The repair candidate now skips individually uninspectable dex holders, passes the pre-isolation 4.2.1 continuity refill through natively, preserves the Current seed\'s absolute position during isolation, and compacts later gaps around that anchor. One bundled device re-acceptance remains pending.\n'
completion.write_text(text)

print('Bundled regression repair applied.')
