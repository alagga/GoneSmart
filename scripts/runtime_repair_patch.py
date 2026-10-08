from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one replacement, found {count}")
    p.write_text(text.replace(old, new, 1))


# Track Mix: after the selected seed has been isolated, never wait for the
# network recommendation pool. Keep that fill running, but let GMMP populate
# Initial Size immediately so queue_position=2 cannot be absent because of
# GoneSmart's own wait.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicy.kt",
    """    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)
""",
    """    /**
     * Once Track Mix has reduced the queue to its selected native seed,
     * playback continuity outranks waiting for the remote Smart-DJ pool.
     * The pool fill may continue in the background, but GMMP must be
     * allowed to populate its Initial Size immediately.
     */
    fun shouldWaitForSmartPoolAfterSeedIsolation(
        explicitInitialRefill: Boolean
    ): Boolean = !explicitInitialRefill

    fun nativeInitialRefillCount(
        initialSize: Int,
        seedQueueSize: Int
    ): Int = (initialSize.coerceAtLeast(1) - seedQueueSize.coerceAtLeast(0))
        .coerceAtLeast(0)
""",
)

replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt",
    """import org.junit.Assert.assertEquals
import org.junit.Test
""",
    """import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
""",
)
replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/TrackMixInitialFillPolicyTest.kt",
    """    @Test fun keepsLegacyCommandBoundaryButRefills421BeforeNextSourceLookup() {
        assertEquals(
            1_800L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true)
        )
        assertEquals(
            90L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false)
        )
    }
""",
    """    @Test fun keepsLegacyCommandBoundaryButRefills421BeforeNextSourceLookup() {
        assertEquals(
            1_800L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(true)
        )
        assertEquals(
            90L,
            TrackMixInitialFillPolicy.autoDjCommandBoundaryWaitMs(false)
        )
    }

    @Test fun isolatedTrackMixSeedNeverWaitsForSmartPool() {
        assertFalse(
            TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(
                explicitInitialRefill = true
            )
        )
        assertTrue(
            TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(
                explicitInitialRefill = false
            )
        )
    }
""",
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    """        Log.i(
            TAG,
            \"SMART DJ WAIT | \" +
                \"pool empty/insufficient - preparing session pool\"
        )

        val fastInitialWait = trackMixController.isExplicitInitialRefill()
        val waitTimeoutMs = if (fastInitialWait) {
            TRACK_MIX_INITIAL_SMART_WAIT_MS
        } else {
            TimeUnit.SECONDS.toMillis(SMART_PREPARE_TIMEOUT_SECONDS)
        }

        return try {
""",
    """        val waitForSmartPool =
            TrackMixInitialFillPolicy.shouldWaitForSmartPoolAfterSeedIsolation(
                explicitInitialRefill = trackMixController.isExplicitInitialRefill()
            )

        if (!waitForSmartPool) {
            // The queue now contains only the selected Track Mix seed.
            // GMMP may request queue_position=2 immediately for its
            // next/gapless decoder. Never block that native Initial Size
            // refill on the network recommendation pipeline. The already
            // started pool fill deliberately keeps running for later
            // ordinary Auto-DJ refills.
            Log.i(
                TAG,
                \"SMART DJ INITIAL CONTINUITY | native refill immediately\" +
                    \" | poolFill=continuing\"
            )
            return false
        }

        Log.i(
            TAG,
            \"SMART DJ WAIT | \" +
                \"pool empty/insufficient - preparing session pool\"
        )

        val waitTimeoutMs =
            TimeUnit.SECONDS.toMillis(SMART_PREPARE_TIMEOUT_SECONDS)

        return try {
""",
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    """        } catch (timeoutException: TimeoutException) {

            if (fastInitialWait) {
                // Do not cancel or invalidate this fill: it becomes the warm
                // pool for ordinary upcoming refills after native GMMP has
                // made Next available immediately.
                Log.i(
                    TAG,
                    \"SMART DJ INITIAL FAST FALLBACK | waitedMs=\" +
                        TRACK_MIX_INITIAL_SMART_WAIT_MS +
                        \" | poolFill=continuing\"
                )
                return false
            }

            pipelineGeneration
""",
    """        } catch (timeoutException: TimeoutException) {

            pipelineGeneration
""",
)
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/GoneSmartModule.kt",
    """        // Track Auto-DJ must never strand playback on the isolated seed while
        // the network recommendation pipeline takes many seconds. Give the
        // smart pool a short head start, then let native GMMP fill Initial
        // Size immediately while the same pool preparation keeps running.
        private const val TRACK_MIX_INITIAL_SMART_WAIT_MS =
            1_500L

""",
    """        // Track Auto-DJ must never strand playback on the isolated seed while
        // the network recommendation pipeline takes many seconds. Once the
        // seed is isolated, the native Initial Size refill is immediate;
        // recommendation preparation may continue only in the background.

""",
)

# Playlist Link: preserve historical holder names only as fast paths. If R8
# moved the constants, discover the unique compatible static field from the
# target dex by semantic value. The already-resolved native IN builder is used
# only as an in-memory semantic probe when the query-field object itself is
# opaque; no database query is executed.
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt",
    """    fun matchesStaticFieldSemanticValue(
        field: Field,
        valueClass: Class<*>,
        expectedValue: String
    ): Boolean {
        if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            return false
        }
        return runCatching {
            field.isAccessible = true
            val value = field.get(null) ?: return@runCatching false
            valueClass.isInstance(value) &&
                semanticRepresentations(value).any {
                    containsSemanticIdentifier(it, expectedValue)
                }
        }.getOrDefault(false)
    }
""",
    """    fun matchesStaticFieldSemanticValue(
        field: Field,
        valueClass: Class<*>,
        expectedValue: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Boolean {
        if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
            return false
        }
        return runCatching {
            field.isAccessible = true
            val value = field.get(null) ?: return@runCatching false
            if (!valueClass.isInstance(value)) return@runCatching false

            val direct = semanticRepresentations(value).any {
                containsSemanticIdentifier(it, expectedValue)
            }
            if (direct) return@runCatching true

            val probed = valueProbe?.invoke(value)
                ?: return@runCatching false
            semanticRepresentations(probed).any {
                containsSemanticIdentifier(it, expectedValue)
            }
        }.getOrDefault(false)
    }
""",
)

resolver_insert = """
    private fun readNamedField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val current = type
            val field = runCatching {
                current.getDeclaredField(name)
            }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
            type = current.superclass
        }
        return null
    }

    /**
     * Enumerate only class names already present in this target ClassLoader's
     * dex files. Classes are loaded without initialization; static values are
     * touched only after their declared field shape is compatible with the
     * native query-field parameter type.
     */
    private fun dexClassNames(loader: ClassLoader): Sequence<String> = sequence {
        val pathList = readNamedField(loader, \"pathList\")
            ?: return@sequence
        val elements = readNamedField(pathList, \"dexElements\")
            ?: return@sequence
        if (!elements.javaClass.isArray) return@sequence

        val seen = linkedSetOf<String>()
        val length = java.lang.reflect.Array.getLength(elements)
        for (index in 0 until length) {
            val element = java.lang.reflect.Array.get(elements, index)
                ?: continue
            val dexFile = readNamedField(element, \"dexFile\")
                ?: continue
            val entries = runCatching {
                dexFile.javaClass.getMethod(\"entries\").invoke(dexFile)
                    as? java.util.Enumeration<*>
            }.getOrNull() ?: continue
            while (entries.hasMoreElements()) {
                val name = entries.nextElement() as? String ?: continue
                if (seen.add(name)) yield(name)
            }
        }
    }

    private fun potentialSemanticHolder(
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

    internal fun resolveStaticFieldBySemanticValue(
        preferredHolders: List<Class<*>>,
        fallbackHolders: Sequence<Class<*>>,
        valueClass: Class<*>,
        expectedValue: String,
        description: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Field {
        fun matches(type: Class<*>): List<Field> =
            fields(type).filter { field ->
                matchesStaticFieldSemanticValue(
                    field = field,
                    valueClass = valueClass,
                    expectedValue = expectedValue,
                    valueProbe = valueProbe
                )
            }

        preferredHolders.distinctBy { it.name }.forEach { holder ->
            val found = matches(holder)
            require(found.size <= 1) {
                \"$description on preferred holder ${holder.name} is ambiguous: \" +
                    found.joinToString { it.name }
            }
            found.singleOrNull()?.let {
                return it.apply { isAccessible = true }
            }
        }

        val discovered = arrayListOf<Field>()
        fallbackHolders
            .distinctBy { it.name }
            .filter { potentialSemanticHolder(it, valueClass) }
            .forEach { holder ->
                val found = matches(holder)
                require(found.size <= 1) {
                    \"$description on discovered holder ${holder.name} is ambiguous: \" +
                        found.joinToString { it.name }
                }
                found.singleOrNull()?.let { field ->
                    discovered += field
                    require(discovered.size <= 1) {
                        \"$description is ambiguous across holders: \" +
                            discovered.joinToString {
                                it.declaringClass.name + \".\" + it.name
                            }
                    }
                }
            }

        require(discovered.size == 1) {
            \"$description is missing after semantic dex discovery\"
        }
        return discovered.single().apply { isAccessible = true }
    }

    fun resolveStaticFieldBySemanticValue(
        loader: ClassLoader,
        preferredHolderNames: List<String>,
        valueClass: Class<*>,
        expectedValue: String,
        description: String,
        valueProbe: ((Any) -> Any?)? = null
    ): Field {
        val preferredNames = preferredHolderNames.distinct()
        val preferred = preferredNames.mapNotNull { name ->
            runCatching { loader.loadClass(name) }.getOrNull()
        }
        val valuePackage = valueClass.name.substringBeforeLast(
            '.',
            missingDelimiterValue = \"\"
        )
        val fallback = dexClassNames(loader)
            .filter { className ->
                className !in preferredNames &&
                    className.substringBeforeLast(
                        '.',
                        missingDelimiterValue = \"\"
                    ) == valuePackage
            }
            .mapNotNull { className ->
                runCatching {
                    Class.forName(className, false, loader)
                }.getOrNull()
            }

        return resolveStaticFieldBySemanticValue(
            preferredHolders = preferred,
            fallbackHolders = fallback,
            valueClass = valueClass,
            expectedValue = expectedValue,
            description = description,
            valueProbe = valueProbe
        )
    }
"""
replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolver.kt",
    "\n    fun method(\n",
    "\n" + resolver_insert + "\n    fun method(\n",
)

replace_once(
    "app/src/main/java/io/github/alagga/gonesmart/PlaylistBridgeController.kt",
    """        val queryFieldClass = nativeIn.parameterTypes[0]
        val trackFieldClass = r.loadClass(
            loader,
            listOf(\"w75\", \"z75\"),
            \"native track query fields\"
        ) { type ->
            r.fields(type).count { field ->
                r.matchesStaticFieldSemanticValue(
                    field = field,
                    valueClass = queryFieldClass,
                    expectedValue = \"track_uri\"
                )
            } == 1
        }
        val uriField = r.field(
            trackFieldClass,
            listOf(\"URI\"),
            \"native track URI query field\"
        ) { field ->
            r.matchesStaticFieldSemanticValue(
                field = field,
                valueClass = queryFieldClass,
                expectedValue = \"track_uri\"
            )
        }.get(null)
            ?: throw IllegalStateException(\"Native track URI query field is null\")
""",
    """        val queryFieldClass = nativeIn.parameterTypes[0]
        val uriFieldMember = r.resolveStaticFieldBySemanticValue(
            loader = loader,
            preferredHolderNames = listOf(\"w75\", \"z75\"),
            valueClass = queryFieldClass,
            expectedValue = \"track_uri\",
            description = \"native track URI query field\",
            valueProbe = { queryField ->
                nativeIn.invoke(
                    null,
                    queryField,
                    listOf(\"gonesmart-playlist-link-binding-probe\")
                )
            }
        )
        val uriField = uriFieldMember.get(null)
            ?: throw IllegalStateException(\"Native track URI query field is null\")
""",
)

# Pure regression coverage for remapped-holder discovery and the optional
# resolved-native-builder semantic probe.
replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt",
    """    private object AmbiguousQueryFieldsFixture {
        @JvmField val a = QueryColumn(\"track_uri\")
        @JvmField val b = QueryColumn(\"track_uri\")
    }
""",
    """    private object AmbiguousQueryFieldsFixture {
        @JvmField val a = QueryColumn(\"track_uri\")
        @JvmField val b = QueryColumn(\"track_uri\")
    }

    private object WrongHistoricalQueryFieldsFixture {
        @JvmField val a = QueryColumn(\"song_id\")
    }

    private object RemappedQueryFieldsFixture {
        @JvmField val x = QueryColumn(\"song_id\")
        @JvmField val y = QueryColumn(\"track_uri\")
    }

    private class BlindQueryColumn : QueryFieldMarker {
        override fun toString(): String = \"opaque-column\"
    }

    private class QueryPredicate(private val sql: String) {
        override fun toString(): String = sql
    }

    private object BlindQueryFieldsFixture {
        @JvmField val id: QueryFieldMarker = BlindQueryColumn()
        @JvmField val uri: QueryFieldMarker = BlindQueryColumn()
    }
""",
)
replace_once(
    "app/src/test/java/io/github/alagga/gonesmart/PlaylistBridgeReflectionResolverTest.kt",
    """    @Test(expected = IllegalArgumentException::class)
    fun semanticStaticFieldAmbiguityFailsClosed() {
        PlaylistBridgeReflectionResolver.field(
            AmbiguousQueryFieldsFixture::class.java,
            listOf(\"URI\"),
            \"semantic query field\"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryColumn::class.java,
                expectedValue = \"track_uri\"
            )
        }
    }
""",
    """    @Test(expected = IllegalArgumentException::class)
    fun semanticStaticFieldAmbiguityFailsClosed() {
        PlaylistBridgeReflectionResolver.field(
            AmbiguousQueryFieldsFixture::class.java,
            listOf(\"URI\"),
            \"semantic query field\"
        ) { candidate ->
            PlaylistBridgeReflectionResolver.matchesStaticFieldSemanticValue(
                field = candidate,
                valueClass = QueryColumn::class.java,
                expectedValue = \"track_uri\"
            )
        }
    }

    @Test
    fun semanticFieldDiscoveryFallsBackBeyondHistoricalHolderNames() {
        val field = PlaylistBridgeReflectionResolver.resolveStaticFieldBySemanticValue(
            preferredHolders = listOf(WrongHistoricalQueryFieldsFixture::class.java),
            fallbackHolders = sequenceOf(RemappedQueryFieldsFixture::class.java),
            valueClass = QueryColumn::class.java,
            expectedValue = \"track_uri\",
            description = \"remapped query field\"
        )
        assertEquals(\"y\", field.name)
        assertSame(RemappedQueryFieldsFixture::class.java, field.declaringClass)
    }

    @Test
    fun semanticFieldDiscoveryMayUseResolvedNativeBuilderAsOpaqueProbe() {
        val field = PlaylistBridgeReflectionResolver.resolveStaticFieldBySemanticValue(
            preferredHolders = emptyList(),
            fallbackHolders = sequenceOf(BlindQueryFieldsFixture::class.java),
            valueClass = QueryFieldMarker::class.java,
            expectedValue = \"track_uri\",
            description = \"opaque query field\",
            valueProbe = { value ->
                when {
                    value === BlindQueryFieldsFixture.uri ->
                        QueryPredicate(\"track_uri IN (?)\")
                    value === BlindQueryFieldsFixture.id ->
                        QueryPredicate(\"song_id IN (?)\")
                    else -> null
                }
            }
        )
        assertEquals(\"uri\", field.name)
    }
""",
)

print("runtime repair patch applied")
