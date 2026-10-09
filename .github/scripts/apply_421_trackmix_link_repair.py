from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one anchor, found {count}: {old[:120]!r}"
        )
    p.write_text(text.replace(old, new, 1))


def insert_before_once(path: str, marker: str, addition: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(marker)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one marker, found {count}: {marker[:120]!r}"
        )
    p.write_text(text.replace(marker, addition + marker, 1))


# Track Mix policy: one immediate continuity row on 4.2.1.
path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt"
replace_once(
    path,
    """ * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. Track Mix therefore suppresses that
 * transitional refill and invokes the same native refill boundary exactly once
 * with the number of rows missing from Initial Size.
""",
    """ * AUTO_DJ command subsequently asks for its regular `upcoming` refill count;
 * that is not the Initial Size contract. Track Mix therefore suppresses that
 * transitional refill. On 4.2.1 it first requests one native continuity row so
 * playback can never observe an empty next slot, then requests the remaining
 * Initial Size rows after the already-running GoneSmart pool had a bounded
 * chance to become ready. Legacy 4.2.0 keeps its accepted single full refill.
""",
)
replace_once(
    path,
    """    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)
}""",
    """    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)

    fun continuityBootstrapRefillCount(
        totalMissing: Int,
        legacyQueue: Boolean
    ): Int {
        val missing = totalMissing.coerceAtLeast(0)
        return if (legacyQueue) missing else missing.coerceAtMost(1)
    }
}""",
)

# Track Mix controller: split initial fill and final safe rebase.
path = "app/src/main/java/io/github/alagga/gonesmart/TrackMixController.kt"
replace_once(
    path,
    """internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean,
    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null }
) {""",
    """internal class TrackMixController(
    private val enableSmartDj: (Context) -> Boolean,
    private val requestNativeRefill: (Int) -> Boolean,
    private val nativePositionWriterProvider: (Any) -> NativeQueuePositionWriter? = { null },
    private val awaitSmartInitialPool: (Any, Int) -> Boolean = { _, _ -> false }
) {""",
)
insert_before_once(
    path,
    """    private fun armManagedAutoDjSession() {
""",
    """    fun rebaseManagedQueueAfterInitialFill(autoDj: Any): Boolean {
        if (managedAutoDj?.get() !== autoDj) return false
        return runCatching {
            GmmpQueueMutationBridge(
                autoDj = autoDj,
                verifiedPositionWriter = nativePositionWriterProvider(autoDj)
            ).rebaseQueueToOneIfNeeded()
        }.onFailure {
            Log.e(
                TAG,
                "MIX QUEUE REBASE | final 4.2.1 Initial Size rebase failed",
                it
            )
        }.getOrDefault(false)
    }

""",
)
p = Path(path)
text = p.read_text()
start_marker = """            val initialRequest =
                TrackMixInitialFillPolicy.nativeInitialRefillCount(
"""
end_marker = """            // Initial Size is now verified (or the bounded fill failed). Only
"""
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("TrackMixController: initial fill block markers missing")
replacement = """            val totalInitialRequest =
                TrackMixInitialFillPolicy.nativeInitialRefillCount(
                    initialSize = initial,
                    seedQueueSize = cleared.ids.size
                )
            val legacyQueue =
                nativeAutoDj?.get()?.let { autoDj ->
                    field(autoDj, "q")?.javaClass?.name == "ex3"
                } == true || nativeQueue?.get()?.javaClass?.name == "ex3"
            val bootstrapRequest =
                TrackMixInitialFillPolicy.continuityBootstrapRefillCount(
                    totalMissing = totalInitialRequest,
                    legacyQueue = legacyQueue
                )
            val smartRemainder =
                (totalInitialRequest - bootstrapRequest).coerceAtLeast(0)

            Log.i(
                TAG,
                "MIX AUTO-DJ | command=sent | initialSize=$initial | " +
                    "upcoming=$upcoming | seed=$selectedId"
            )
            Log.i(
                TAG,
                "MIX INITIAL FILL | initial=$initial | " +
                    "seedSize=${cleared.ids.size} | totalMissing=$totalInitialRequest | " +
                    "bootstrap=$bootstrapRequest | smartRemainder=$smartRemainder | " +
                    "legacy=$legacyQueue"
            )

            val bootstrapAccepted =
                requestHeldNativeRefill(bootstrapRequest)
            val smartPoolReady = if (
                bootstrapAccepted &&
                smartRemainder > 0 &&
                !legacyQueue
            ) {
                val autoDj = nativeAutoDj?.get()
                autoDj != null &&
                    awaitSmartInitialPool(autoDj, smartRemainder)
            } else {
                true
            }
            if (smartRemainder > 0 && !legacyQueue) {
                Log.i(
                    TAG,
                    "MIX SMART REMAINDER | requested=$smartRemainder | " +
                        "poolReady=$smartPoolReady | " +
                        if (smartPoolReady) {
                            "source=GoneSmart"
                        } else {
                            "source=native-fallback-after-bounded-wait"
                        }
                )
            }
            val remainderAccepted = if (
                bootstrapAccepted &&
                smartRemainder > 0
            ) {
                requestHeldNativeRefill(smartRemainder)
            } else {
                true
            }
            val fillRequestAccepted =
                bootstrapAccepted && remainderAccepted
            var filled = if (!fillRequestAccepted) {
                Log.w(TAG, "MIX INITIAL FILL | native refill request failed")
                null
            } else {
                awaitQueue(request, 65_000L) {
                    it.currentId == selectedId &&
                        it.ids.size == initial
                }
            }

            if (filled != null && !legacyQueue) {
                val autoDj = nativeAutoDj?.get()
                val rebased = autoDj != null &&
                    rebaseManagedQueueAfterInitialFill(autoDj)
                if (rebased) {
                    filled = queueSnapshot()?.takeIf { snapshot ->
                        snapshot.currentId == selectedId &&
                            snapshot.ids.size == initial
                    } ?: filled
                    Log.i(
                        TAG,
                        "MIX INITIAL REBASE | queue=1..$initial | " +
                            "currentEntryPreserved=true"
                    )
                } else {
                    Log.w(
                        TAG,
                        "MIX INITIAL REBASE | skipped/failed; " +
                            "verified queue retained without destructive fallback"
                    )
                }
            }

"""
p.write_text(text[:start] + replacement + text[end:])

# Queue policy: keep anchored compaction while filling, then allow final 1..N.
path = "app/src/main/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicy.kt"
p = Path(path)
text = p.read_text()
insert = """
    fun rebaseToOne(rows: List<Row>, currentQueueId: Long): Plan? {
        if (rows.isEmpty()) return null
        require(rows.all { it.position > 0 }) {
            "Queue positions must be positive"
        }
        require(rows.map { it.queueId }.toSet().size == rows.size) {
            "Queue IDs must be unique"
        }
        require(rows.map { it.position }.toSet().size == rows.size) {
            "Queue positions must be unique"
        }

        val ordered = rows.sortedBy { it.position }
        val currentIndex = ordered.indexOfFirst { it.queueId == currentQueueId }
        require(currentIndex >= 0) {
            "Current queue entry must exist in the native rows"
        }

        val original = ordered.map { it.position }
        val normalized = List(ordered.size) { index -> index + 1 }
        if (original == normalized) return null

        return Plan(
            orderedQueueIds = ordered.map { it.queueId },
            originalPositions = original,
            normalizedPositions = normalized,
            currentQueueId = currentQueueId,
            currentNewPosition = currentIndex + 1
        )
    }
"""
marker = "\n}\n"
if not text.endswith(marker):
    raise SystemExit("QueuePositionNormalizationPolicy: unexpected ending")
p.write_text(text[: -len(marker)] + insert + marker)

# Native queue bridge: post-fill rebase through the proven current writer.
path = "app/src/main/java/io/github/alagga/gonesmart/GmmpQueueMutationBridge.kt"
insert_before_once(
    path,
    """    private fun resolve(
        requireDelete: Boolean,
        requireStatePosition: Boolean
    ): Resolved {
""",
    """    fun rebaseQueueToOneIfNeeded(): Boolean {
        val resolved = resolve(
            requireDelete = false,
            requireStatePosition = true
        )
        val ordered = resolved.rows.sortedBy {
            number(resolved.position, it).toInt()
        }
        if (ordered.isEmpty()) return true

        val current = resolved.context.items.singleOrNull {
            it.state == QueueItemState.CURRENT
        } ?: error("GMMP current queue entry unavailable for final rebase")
        val plan = QueuePositionNormalizationPolicy.rebaseToOne(
            rows = ordered.map {
                QueuePositionNormalizationPolicy.Row(
                    queueId = number(resolved.queueId, it).toLong(),
                    position = number(resolved.position, it).toInt()
                )
            },
            currentQueueId = current.queueEntryId
        ) ?: return true
        val statePosition = resolved.statePosition
            ?: error("GMMP current-position writer unavailable for final rebase")
        val oldPositions = ordered.map {
            number(resolved.position, it).toInt()
        }
        val oldState = statePosition.read()

        try {
            ordered.forEachIndexed { index, row ->
                setInt(
                    resolved.position,
                    row,
                    plan.normalizedPositions[index]
                )
            }
            resolved.update.invoke(
                resolved.dao,
                ArrayList(ordered)
            )
            statePosition.write(plan.currentNewPosition)

            val verified = GmmpQueueReader().read(
                autoDj,
                statePosition.read()
            ) ?: error("Queue final-rebase verification unavailable")
            val verifiedOrdered = verified.items.sortedBy { it.queuePosition }
            val verifiedIds = verifiedOrdered.map { it.queueEntryId }
            val verifiedPositions = verifiedOrdered.map { it.queuePosition }
            val verifiedCurrent = verified.items.singleOrNull {
                it.state == QueueItemState.CURRENT
            }?.queueEntryId
            require(
                verifiedIds == plan.orderedQueueIds &&
                    verifiedPositions == plan.normalizedPositions &&
                    verifiedCurrent == plan.currentQueueId &&
                    verified.currentQueuePosition == plan.currentNewPosition
            ) {
                "GMMP Track Auto-DJ final queue rebase failed postcondition"
            }

            Log.i(
                TAG,
                "QUEUE POSITION REBASE | old=" +
                    plan.originalPositions.joinToString(",") +
                    " | new=" + plan.normalizedPositions.joinToString(",") +
                    " | currentId=" + plan.currentQueueId +
                    " | currentPosition=" + plan.currentNewPosition +
                    " | verified=true"
            )
            return true
        } catch (failure: Throwable) {
            runCatching {
                ordered.forEachIndexed { index, row ->
                    setInt(resolved.position, row, oldPositions[index])
                }
                resolved.update.invoke(
                    resolved.dao,
                    ArrayList(ordered)
                )
                statePosition.write(oldState)
            }.onFailure { rollback ->
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

""",
)

# Module: bounded wait for Smart remainder only after one native next row exists.
path = "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt"
replace_once(
    path,
    """        private const val SMART_PREPARE_TIMEOUT_SECONDS =
            60L

        // Track Auto-DJ must never strand playback on the isolated seed while
""",
    """        private const val SMART_PREPARE_TIMEOUT_SECONDS =
            60L

        private const val TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS =
            12L

        // Track Auto-DJ must never strand playback on the isolated seed while
""",
)
replace_once(
    path,
    """        nativePositionWriterProvider = { autoDj ->
            queueFlipController.verifiedPositionWriter(autoDj)
        }
    )""",
    """        nativePositionWriterProvider = { autoDj ->
            queueFlipController.verifiedPositionWriter(autoDj)
        },
        awaitSmartInitialPool = { autoDj, count ->
            awaitTrackMixSmartInitialPool(autoDj, count)
        }
    )""",
)
insert_before_once(
    path,
    """    private fun ensureRecommendationPoolReady(
""",
    """    private fun awaitTrackMixSmartInitialPool(
        autoDjInstance: Any,
        requestedTracks: Int
    ): Boolean {
        if (requestedTracks <= 0) return true

        fun readyNow(): Boolean {
            val context = readQueueContext(autoDjInstance) ?: return false
            val session = queueSessionTracker.observe(context)
            return recommendationPool.hasEnough(
                expectedSessionId = session.sessionId,
                count = requestedTracks,
                excludedTrackIds = context.items.map { it.track.id }.toSet()
            )
        }

        if (readyNow()) {
            Log.i(
                TAG,
                "SMART DJ TRACK MIX REMAINDER | pool already ready | " +
                    "requested=$requestedTracks"
            )
            return true
        }

        val future = synchronized(poolFillLock) {
            activePoolFillFuture
        } ?: return false

        val completed = try {
            future.get(
                TRACK_MIX_SMART_REMAINDER_WAIT_SECONDS,
                TimeUnit.SECONDS
            )
        } catch (_: TimeoutException) {
            Log.w(
                TAG,
                "SMART DJ TRACK MIX REMAINDER | bounded wait timed out | " +
                    "requested=$requestedTracks | poolFill=continuing"
            )
            false
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "SMART DJ TRACK MIX REMAINDER | pool wait failed; " +
                    "native fallback remains available",
                failure
            )
            false
        }

        val ready = completed && readyNow()
        Log.i(
            TAG,
            "SMART DJ TRACK MIX REMAINDER | wait complete | " +
                "requested=$requestedTracks | ready=$ready"
        )
        return ready
    }

""",
)

# Safe direct database read facade for Playlist Link URI -> song_id lookup.
path = "app/src/main/java/io/github/alagga/gonesmart/GmmpReadOnlySql.kt"
insert_before_once(
    path,
    """    fun canResolve(autoDjInstance: Any): Boolean =
""",
    """    private class DirectDatabaseHost(
        val database: Any
    )

    fun <T> queryDatabase(
        databaseInstance: Any,
        sql: String,
        args: Array<Any?> = emptyArray(),
        reader: (Cursor) -> T
    ): T = query(
        autoDjInstance = DirectDatabaseHost(databaseInstance),
        sql = sql,
        args = args,
        reader = reader
    )

""",
)

# Native Smart-rule helper for accepted Track-ID equality semantics.
path = "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeNativeSentinelPolicy.kt"
replace_once(
    path,
    """    fun ruleArguments(shouldMatch: Boolean): Array<Any?> =
        arrayOf(
            TRACK_ID_FIELD,
            if (shouldMatch) NOT_EQUALS_OPERATOR else EQUALS_OPERATOR,
            Long.MIN_VALUE.toString(),
            0
        )
}""",
    """    fun ruleArguments(shouldMatch: Boolean): Array<Any?> =
        arrayOf(
            TRACK_ID_FIELD,
            if (shouldMatch) NOT_EQUALS_OPERATOR else EQUALS_OPERATOR,
            Long.MIN_VALUE.toString(),
            0
        )

    fun trackIdEqualsArguments(trackId: Long): Array<Any?> =
        arrayOf(
            TRACK_ID_FIELD,
            EQUALS_OPERATOR,
            trackId.toString(),
            0
        )
}""",
)

# Bounded read-only extraction of the query-field object from a compiled native predicate.
path = "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt"
insert_before_once(
    path,
    """    private fun readNamedField(instance: Any, name: String): Any? {
""",
    """    internal fun uniqueInstanceInObjectGraph(
        root: Any,
        valueClass: Class<*>,
        maxDepth: Int = 6
    ): Any? {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val matches = java.util.IdentityHashMap<Any, Boolean>()

        fun visit(candidate: Any?, depth: Int) {
            if (candidate == null || depth < 0) return
            if (valueClass.isInstance(candidate)) {
                matches[candidate] = true
                return
            }
            when (candidate) {
                is CharSequence, is Number, is Boolean, is Char,
                is Enum<*>, is Class<*> -> return
                is Collection<*> -> {
                    candidate.take(256).forEach { visit(it, depth - 1) }
                    return
                }
                is Map<*, *> -> {
                    candidate.entries.take(256).forEach { entry ->
                        visit(entry.key, depth - 1)
                        visit(entry.value, depth - 1)
                    }
                    return
                }
            }
            if (candidate.javaClass.isArray) {
                val length = java.lang.reflect.Array.getLength(candidate)
                repeat(length.coerceAtMost(256)) { index ->
                    visit(java.lang.reflect.Array.get(candidate, index), depth - 1)
                }
                return
            }
            if (seen.put(candidate, true) != null || depth == 0) return

            var type: Class<*>? = candidate.javaClass
            while (type != null && type != Any::class.java) {
                val name = type.name
                if (
                    name.startsWith("java.") ||
                    name.startsWith("kotlin.") ||
                    name.startsWith("android.")
                ) break
                declaredFieldsSafely(type)
                    .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        runCatching {
                            field.isAccessible = true
                            visit(field.get(candidate), depth - 1)
                        }
                    }
                type = runCatching { type.superclass }.getOrNull()
            }
        }

        visit(root, maxDepth)
        return matches.keys.toList().singleOrNull()
    }

""",
)

# Playlist Link: static track_uri discovery is no longer a startup gate.
path = "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
replace_once(
    path,
    """        val nativeIn: Method,
        val uriField: Any,
        val whereGroupConstructor: Constructor<*>?,
""",
    """        val nativeIn: Method,
        val trackIdField: Any?,
        val whereGroupConstructor: Constructor<*>?,
""",
)
p = Path(path)
text = p.read_text()
old = """        val clauses = membership.paths
            .distinct()
            .chunked(MAX_IN_VALUES)
            .map { values -> native.nativeIn.invoke(null, native.uriField, values) }
        val result = if (clauses.size == 1) {
            clauses.single()
        } else {
            val group = native.whereGroupConstructor
            if (group == null) {
                Log.e(
                    TAG,
                    "BRIDGE COMPILE | native OR predicate group unavailable; " +
                        "failing closed | chunks=${clauses.size}"
                )
                return falsePredicate(native)
            }
            group.newInstance(clauses, "OR")
        }
        Log.i(
            TAG,
            "BRIDGE COMPILE | entries=${membership.paths.size}" +
                " | chunks=${clauses.size}" +
                " | elapsedMs=${(System.nanoTime() - started) / 1_000_000L}" +
                " | " + PlaylistBridgePolicy.safePath(reference.path)
        )
        return result
"""
new = """        val trackIds = runCatching {
            resolveTrackIds(native, membership.paths)
        }.onFailure {
            Log.e(TAG, "BRIDGE COMPILE | native track-id lookup failed", it)
        }.getOrNull() ?: return falsePredicate(native)
        if (trackIds.isEmpty()) {
            Log.i(
                TAG,
                "BRIDGE COMPILE | source has no GMMP library tracks -> false predicate"
            )
            return falsePredicate(native)
        }

        val nativeTrackIdField = native.trackIdField
        val strategy: String
        val clauses: List<Any> = if (nativeTrackIdField != null) {
            strategy = "native-id-in"
            trackIds.chunked(MAX_IN_VALUES).map { values ->
                native.nativeIn.invoke(null, nativeTrackIdField, values)
                    ?: throw IllegalStateException(
                        "GMMP native Track-ID IN builder returned null"
                    )
            }
        } else {
            if (trackIds.size > MAX_IN_VALUES) {
                Log.e(
                    TAG,
                    "BRIDGE COMPILE | Track-ID field is opaque and equality " +
                        "fallback exceeds bounded clause count; failing closed | " +
                        "tracks=${trackIds.size}"
                )
                return falsePredicate(native)
            }
            strategy = "native-id-equality"
            trackIds.map { trackId ->
                val nativeRule = native.smartRuleConstructor.newInstance(
                    *PlaylistBridgeNativeSentinelPolicy.trackIdEqualsArguments(trackId)
                )
                native.evaluationMethod.invoke(
                    nativeRule,
                    java.util.LinkedHashSet<Any>(),
                    0
                ) ?: throw IllegalStateException(
                    "GMMP native Track-ID rule evaluator returned null"
                )
            }
        }

        val result = if (clauses.size == 1) {
            clauses.single()
        } else {
            val group = native.whereGroupConstructor
            if (group == null) {
                Log.e(
                    TAG,
                    "BRIDGE COMPILE | native OR predicate group unavailable; " +
                        "failing closed | clauses=${clauses.size}"
                )
                return falsePredicate(native)
            }
            group.newInstance(clauses, "OR")
        }
        Log.i(
            TAG,
            "BRIDGE COMPILE | entries=${membership.paths.size}" +
                " | libraryTrackIds=${trackIds.size}" +
                " | clauses=${clauses.size}" +
                " | strategy=$strategy" +
                " | elapsedMs=${(System.nanoTime() - started) / 1_000_000L}" +
                " | " + PlaylistBridgePolicy.safePath(reference.path)
        )
        return result
"""
if text.count(old) != 1:
    raise SystemExit(f"PlaylistBridgeController compile block count={text.count(old)}")
p.write_text(text.replace(old, new, 1))

insert_before_once(
    path,
    """    private fun falsePredicate(native: Bindings): Any {
""",
    """    private fun resolveTrackIds(
        native: Bindings,
        paths: List<String>
    ): List<Long> {
        val database = native.databaseSingleton.get(null)
            ?: throw IllegalStateException("GMDatabase singleton is null")
        val result = linkedSetOf<Long>()
        paths.distinct().chunked(MAX_IN_VALUES).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = Array<Any?>(chunk.size) { index -> chunk[index] }
            val ids = GmmpReadOnlySql.queryDatabase(
                databaseInstance = database,
                sql = "SELECT song_id FROM tracks WHERE track_uri IN (" +
                    placeholders + ")",
                args = args
            ) { cursor ->
                val idColumn = cursor.getColumnIndex("song_id")
                require(idColumn >= 0) {
                    "GMMP track-id query did not expose song_id"
                }
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(idColumn))
                }
            }
            result.addAll(ids)
        }
        Log.i(
            TAG,
            "BRIDGE SOURCE IDS | paths=${paths.size} | library=${result.size}"
        )
        return result.toList()
    }

""",
)

p = Path(path)
text = p.read_text()
start_marker = """        val queryFieldClass = nativeIn.parameterTypes[0]
        val uriFieldMember = r.resolveStaticFieldBySemanticValue(
"""
end_marker = """
        // OR grouping is required only when one source exceeds the
"""
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("PlaylistBridgeController URI binding block markers missing")
replacement = """        val queryFieldClass = nativeIn.parameterTypes[0]
        val trackIdField = r.uniqueInstanceInObjectGraph(
            root = failClosedPredicateProbe,
            valueClass = queryFieldClass
        )
        if (trackIdField == null) {
            Log.w(
                TAG,
                "BRIDGE MAPPING | native Track-ID query field is opaque; " +
                    "using native Track-ID equality evaluator fallback"
            )
        }
"""
p.write_text(text[:start] + replacement + text[end:])

replace_once(
    path,
    """                " | predicate=${predicateClass.name}" +
                " | uriField=" + uriField.toString() +
                " | orGroup=" +
""",
    """                " | predicate=${predicateClass.name}" +
                " | trackIdPredicate=" +
                (if (trackIdField != null) "native-in" else "native-equality") +
                " | orGroup=" +
""",
)
replace_once(
    path,
    """            nativeIn = nativeIn,
            uriField = uriField,
            whereGroupConstructor = whereGroupConstructor,
""",
    """            nativeIn = nativeIn,
            trackIdField = trackIdField,
            whereGroupConstructor = whereGroupConstructor,
""",
)

# Regression tests.
path = "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt"
insert_before_once(
    path,
    """    @Test fun isolatedTrackMixSeedNeverWaitsForSmartPool() {
""",
    """    @Test fun gmmp421BootstrapsOnlyOneContinuityTrackBeforeSmartRemainder() {
        val totalMissing =
            TrackMixInitialFillPolicy.nativeInitialRefillCount(
                initialSize = 5,
                seedQueueSize = 1
            )
        assertEquals(
            1,
            TrackMixInitialFillPolicy.continuityBootstrapRefillCount(
                totalMissing = totalMissing,
                legacyQueue = false
            )
        )
        assertEquals(
            4,
            TrackMixInitialFillPolicy.continuityBootstrapRefillCount(
                totalMissing = totalMissing,
                legacyQueue = true
            )
        )
    }

""",
)

path = "app/src/test/java/io/github/alagga/gonesmart/QueuePositionNormalizationPolicyTest.kt"
insert_before_once(
    path,
    """    @Test
    fun `already contiguous positions need no native mutation`() {
""",
    """    @Test
    fun `verified full Track Mix queue rebases to one through current identity`() {
        val plan = QueuePositionNormalizationPolicy.rebaseToOne(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(50L, 8),
                QueuePositionNormalizationPolicy.Row(51L, 9),
                QueuePositionNormalizationPolicy.Row(52L, 10),
                QueuePositionNormalizationPolicy.Row(53L, 11),
                QueuePositionNormalizationPolicy.Row(54L, 12)
            ),
            currentQueueId = 50L
        )!!

        assertEquals(listOf(1, 2, 3, 4, 5), plan.normalizedPositions)
        assertEquals(1, plan.currentNewPosition)
        assertEquals(50L, plan.currentQueueId)
    }

    @Test
    fun `final rebase keeps a middle current entry by identity`() {
        val plan = QueuePositionNormalizationPolicy.rebaseToOne(
            rows = listOf(
                QueuePositionNormalizationPolicy.Row(60L, 8),
                QueuePositionNormalizationPolicy.Row(61L, 9),
                QueuePositionNormalizationPolicy.Row(62L, 10),
                QueuePositionNormalizationPolicy.Row(63L, 11),
                QueuePositionNormalizationPolicy.Row(64L, 12)
            ),
            currentQueueId = 62L
        )!!

        assertEquals(listOf(1, 2, 3, 4, 5), plan.normalizedPositions)
        assertEquals(3, plan.currentNewPosition)
        assertEquals(62L, plan.currentQueueId)
    }

""",
)

path = "app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt"
replace_once(
    path,
    """    private class QueryPredicate(private val sql: String) {
        override fun toString(): String = sql
    }
""",
    """    private class QueryPredicate(private val sql: String) {
        override fun toString(): String = sql
    }

    private class NativePredicateGraph(
        @Suppress("unused") private val field: QueryFieldMarker,
        @Suppress("unused") private val nested: Any? = null
    )
""",
)
insert_before_once(
    path,
    """    @Test
    fun semanticStaticFieldFallbackIgnoresObfuscatedName() {
""",
    """    @Test
    fun nativeQueryFieldCanBeRecoveredFromCompiledPredicateGraph() {
        val field = QueryColumn("song_id")
        val root = NativePredicateGraph(
            field = field,
            nested = listOf(field)
        )

        assertSame(
            field,
            PlaylistBridgeReflectionResolver.uniqueInstanceInObjectGraph(
                root = root,
                valueClass = QueryFieldMarker::class.java
            )
        )
    }

    @Test
    fun compiledPredicateGraphAmbiguityFailsClosed() {
        val root = NativePredicateGraph(
            field = QueryColumn("song_id"),
            nested = QueryColumn("track_uri")
        )

        assertNull(
            PlaylistBridgeReflectionResolver.uniqueInstanceInObjectGraph(
                root = root,
                valueClass = QueryFieldMarker::class.java
            )
        )
    }

""",
)

controller = Path(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt"
).read_text()
forbidden = """resolveStaticFieldBySemanticValue(
            loader = loader,
            preferredHolderNames = listOf("w75", "z75")"""
if forbidden in controller:
    raise SystemExit("Playlist Link still contains old critical track_uri dex binding")
