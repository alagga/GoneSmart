package io.github.alagga.gonesmart

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dalvik.system.DexFile
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Independent observation family for output-specific GMMP audio profiles.
 *
 * GMMP's native state is observed read-only. The GMMP-side libxposed
 * RemotePreferences implementation is intentionally treated as read-only too:
 * observations are sent to the GoneSmart companion process, which owns the
 * writable settings mirror. Native application is handled separately by the
 * fail-closed audio-profile apply module.
 */
class GoneSmartAudioModule : XposedModule() {

    companion object {
        private const val TAG = "GoneSmartAudioProfiles"
        private const val PROBE_TAG = "GoneSmartAudioProbe"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val COMPANION_PACKAGE = "io.github.alagga.gonesmart"
        private const val CONTEXT_RETRY_MS = 250L
        private const val CONTEXT_MAX_ATTEMPTS = 40
        private const val PREF_PROBE_WINDOW_MS = 10 * 60 * 1000L
        private const val PREF_PROBE_MAX_EVENTS = 200
        private const val PRESET_CAPTURE_WINDOW_MS = 15_000L
        private const val PRESET_READBACK_DELAY_MS = 900L
        private const val PRESET_RECENT_SHAPE_MS = 400L
        private const val STANDARD_SNAPSHOT_DELAY_MS = 350L
        private const val MAX_DYNAMIC_EQ_CLASSES = 12_000
        private const val READER_EPSILON = 0.001
        private const val RUNTIME_PREFS = "gonesmart_audio_runtime_state"
        private const val STANDARD_PREFIX = "standard."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioPreferences: SharedPreferences? = null
    private var activeRouteId: String = GoneSmartAudioProfiles.LOCAL_ROUTE_ID
    private var routeObserver: AudioRouteObserver? = null
    private var nativePreferenceSnapshotDone = false
    private var equalizerObserverInstalled = false

    @Volatile
    private var hostApplication: Application? = null

    @Volatile
    private var currentNativePreset: String? = null

    @Volatile
    private var currentEqPre: Double? = null

    @Volatile
    private var pendingPresetName: String? = null

    @Volatile
    private var pendingPresetUntilElapsed: Long = 0L

    @Volatile
    private var lastCompleteShapeElapsed: Long = 0L

    @Volatile
    private var eqStateInstance: Any? = null

    @Volatile
    private var eqObservationHandler: Handler? = null

    @Volatile
    private var eqPreGetter: Method? = null

    @Volatile
    private var eqBandArrayField: Field? = null

    @Volatile
    private var preReaderVerified = false

    @Volatile
    private var observedEqBandCount: Int? = null

    private val currentEqBands = ConcurrentHashMap<Int, Double>()
    private val verifiedBandReaderIndices = ConcurrentHashMap.newKeySet<Int>()
    private val publishedNativeValues = ConcurrentHashMap<String, String>()
    private val publishedPresetFingerprints = ConcurrentHashMap<String, String>()
    private val preferenceProbeStartedAt = SystemClock.elapsedRealtime()
    private val preferenceProbeEvents = AtomicInteger(0)
    private val preferenceProbeSeen = ConcurrentHashMap.newKeySet<String>()
    private val presetCatalogStarted = AtomicBoolean(false)
    private val bridgeProviderVerified = AtomicBoolean(false)
    private val bridgeFallbackLogged = AtomicBoolean(false)

    private val audioPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (GoneSmartAudioProfiles.isOverrideKey(key)) {
            logActiveOverrideState("preference changed: $key")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != GMMP_PACKAGE) return

        audioPreferences = runCatching {
            getRemotePreferences(GoneSmartAudioProfiles.GROUP).also {
                it.registerOnSharedPreferenceChangeListener(audioPreferenceListener)
            }
        }.onFailure {
            Log.w(TAG, "Audio-profile preferences unavailable; route observation will still start", it)
        }.getOrNull()

        Log.i(TAG, "Bluetooth audio-profile feature loaded")
        installNativePreferenceObserver()
        resolveApplicationAndStart(param.classLoader, attempt = 0)
    }

    private fun resolveApplicationAndStart(classLoader: ClassLoader, attempt: Int) {
        val application = currentApplication()
        if (application != null) {
            hostApplication = application
            AudioProfilePresetStorage.ensureCurrent(
                application.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            )

            // Resolve the route first so every startup native observation is
            // explicitly attributed to Standard or the active Bluetooth output.
            if (routeObserver == null) {
                routeObserver = AudioRouteObserver(application) { route -> onRouteChanged(application, route) }
                routeObserver?.start()
            }

            if (!equalizerObserverInstalled) {
                equalizerObserverInstalled = installEqualizerObserver(classLoader, application)
            }

            if (!nativePreferenceSnapshotDone) {
                nativePreferenceSnapshotDone = true
                snapshotNativePreferences(application)
            }

            importNativePresetCatalog(application)
            return
        }

        if (attempt >= CONTEXT_MAX_ATTEMPTS) {
            Log.w(TAG, "Could not obtain GMMP application context; audio-route observation unavailable")
            return
        }
        mainHandler.postDelayed(
            { resolveApplicationAndStart(classLoader, attempt + 1) },
            CONTEXT_RETRY_MS
        )
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication").apply { isAccessible = true }
        method.invoke(null) as? Application
    }.getOrNull()

    private fun onRouteChanged(application: Application, route: AudioRouteProfile) {
        activeRouteId = route.id
        val extras = Bundle().apply {
            putString(AudioProfileEventReceiver.EXTRA_ROUTE_ID, route.id)
            putString(AudioProfileEventReceiver.EXTRA_ROUTE_LABEL, route.label)
            putBoolean(AudioProfileEventReceiver.EXTRA_ROUTE_BLUETOOTH, route.bluetooth)
            putLong(AudioProfileEventReceiver.EXTRA_LAST_SEEN, route.lastSeenEpochMs)
        }
        val bridged = AudioProfileCompanionBridge.report(
            application,
            AudioProfileCompanionBridge.METHOD_ROUTE,
            extras
        )
        if (bridged) {
            logBridgeProviderVerified()
        } else {
            val intent = Intent(AudioProfileEventReceiver.ACTION_ROUTE_OBSERVED).apply { putExtras(extras) }
            sendFallbackBroadcast(application, intent, "route")
        }
        logActiveOverrideState("route changed")

        if (AudioProfileRuntimePolicy.isStandardRoute(route.id)) {
            // The apply module restores Standard first when a complete baseline
            // exists and otherwise deliberately performs no mutation. Delay this
            // bounded read so unchanged native values are re-attributed to the
            // local route after the route transition has settled.
            mainHandler.postDelayed({
                if (activeRouteId == route.id) snapshotStandardState(application)
            }, STANDARD_SNAPSHOT_DELAY_MS)
        }
    }

    private fun logActiveOverrideState(reason: String) {
        if (AudioProfileRuntimePolicy.isStandardRoute(activeRouteId)) {
            Log.i(TAG, "PROFILE STANDARD | route=$activeRouteId | reason=$reason | native GMMP baseline active")
            return
        }

        val prefs = audioPreferences ?: return
        val prefix = GoneSmartAudioProfiles.overrideKey(activeRouteId, "")
        val overrides = prefs.all.keys.filter { it.startsWith(prefix) && prefs.getBoolean(it, false) }
        if (overrides.isEmpty()) {
            Log.i(TAG, "PROFILE INHERIT | route=$activeRouteId | reason=$reason | Standard baseline")
        } else {
            Log.i(
                TAG,
                "PROFILE OVERRIDE | route=$activeRouteId | overrides=${overrides.size}" +
                    " | reason=$reason | native apply module owns mutation"
            )
        }
    }

    /** One bounded startup read of GMMP SharedPreferences. */
    private fun snapshotNativePreferences(application: Application) {
        val candidates = collectNativePreferenceCandidates(application, includePresetHints = true)
        candidates.forEach { (key, entries) ->
            val mapped = entries.mapNotNull { (_, value) -> NativeAudioObservation.fromPreference(key, value) }
            val distinct = mapped.map { it.value }.distinct()
            if (distinct.size == 1) {
                observeNativePreference(key, entries.first().second, "startup:${entries.first().first}")
            } else if (distinct.size > 1) {
                Log.w(TAG, "Native preference '$key' is ambiguous across GMMP stores; leaving it unobserved")
            }
        }
    }

    /**
     * Re-publish unchanged native values when Standard becomes active. The
     * normal live mirror is globally deduplicated, so without this bounded
     * route-scoped snapshot an unchanged value first seen on Bluetooth would
     * never be promoted into the saved Standard baseline.
     */
    private fun snapshotStandardState(application: Application) {
        if (!AudioProfileRuntimePolicy.isStandardRoute(activeRouteId)) return
        val candidates = collectNativePreferenceCandidates(application, includePresetHints = false)
        var preferenceFields = 0
        candidates.forEach { (key, entries) ->
            val mapped = entries.mapNotNull { (_, value) -> NativeAudioObservation.fromPreference(key, value) }
            val distinct = mapped.distinctBy { it.value }
            if (distinct.size == 1) {
                publishNativeValue(distinct.single(), force = true)
                preferenceFields += 1
                if (key == NativeAudioObservation.PREF_EQ_PRESET) currentNativePreset = distinct.single().value
            }
        }

        val shape = readCurrentEqualizerShape() ?: shapeFromObservedSetters()
        var shapeFields = 0
        shape?.forEach { (fieldId, value) ->
            publishNativeValue(NativeAudioObservation.Value(fieldId, value), force = true)
            shapeFields += 1
        }
        Log.i(
            TAG,
            "STANDARD SNAPSHOT | preferenceFields=$preferenceFields" +
                " | eqShape=$shapeFields/${GoneSmartAudioProfiles.equalizerShapeFieldIds.size}"
        )
    }

    private fun collectNativePreferenceCandidates(
        application: Application,
        includePresetHints: Boolean
    ): Map<String, MutableList<Pair<String, Any?>>> {
        val directory = File(application.applicationInfo.dataDir, "shared_prefs")
        val candidates = mutableMapOf<String, MutableList<Pair<String, Any?>>>()
        directory.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val name = file.name.removeSuffix(".xml")
                val prefs = runCatching {
                    application.getSharedPreferences(name, Context.MODE_PRIVATE)
                }.getOrNull() ?: return@forEach

                NativeAudioObservation.preferenceKeys.forEach { key ->
                    if (prefs.contains(key)) {
                        candidates.getOrPut(key) { mutableListOf() }.add(name to prefs.all[key])
                    }
                }

                if (includePresetHints && BuildConfig.DEBUG) {
                    prefs.all.keys
                        .filter { it.contains("preset", ignoreCase = true) }
                        .take(24)
                        .forEach { key ->
                            val value = prefs.all[key]
                            Log.i(
                                PROBE_TAG,
                                "PRESET STORE HINT | store=$name | key=$key | type=${value?.javaClass?.name ?: "none"}" +
                                    if (value is Set<*>) " | size=${value.size}" else ""
                            )
                        }
                }
            }
        return candidates
    }

    /** Resolve the Equalizer observation owner semantically. */
    private fun installEqualizerObserver(classLoader: ClassLoader, application: Application): Boolean {
        val fast = GmmpEqualizerObservationResolver.resolveFastPath(classLoader)
        val mapping = fast ?: GmmpEqualizerObservationResolver.resolveDynamic(
            discoverEqualizerCandidates(classLoader, application)
        )
        if (mapping == null) {
            Log.w(TAG, "EQ state observer unavailable; structural resolver found no unique candidate")
            return false
        }

        eqPreGetter = mapping.owner.declaredMethods
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .filter { it.parameterCount == 0 && it.returnType == Double::class.javaPrimitiveType }
            .singleOrNull()
            ?.apply { isAccessible = true }
        eqBandArrayField = mapping.owner.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filter { it.type == DoubleArray::class.java }
            .singleOrNull()
            ?.apply { isAccessible = true }

        GmmpEqualizerRuntimeBridge.registerSetters(
            mapping.preSetter,
            mapping.bandSetter,
            mapping.enabledSetter
        )

        Log.i(
            TAG,
            "EQ OBSERVER RESOLVED | source=${mapping.source} | owner=${mapping.owner.name} | score=${mapping.score}" +
                " | readback=${eqPreGetter != null && eqBandArrayField != null} | applyBridge=registered"
        )

        val installed = listOf(
            mapping.preSetter to EqualizerObservationRole.PRE,
            mapping.bandSetter to EqualizerObservationRole.BAND,
            mapping.enabledSetter to EqualizerObservationRole.ENABLED
        ).all { (method, role) -> installEqualizerMethodObserver(method, role) }

        if (!installed) Log.w(TAG, "EQ observer resolved but one or more passive hooks could not be installed")
        return installed
    }

    private enum class EqualizerObservationRole { PRE, BAND, ENABLED }

    private fun installEqualizerMethodObserver(method: Method, role: EqualizerObservationRole): Boolean =
        runCatching {
            method.isAccessible = true
            hook(method).intercept { chain ->
                val args = method.parameterTypes.indices.map { index ->
                    runCatching { chain.getArg(index) }.getOrNull()
                }
                val caller = if (BuildConfig.DEBUG) preferenceProbeCallSite() else null
                val instance = chain.thisObject
                val looper = Looper.myLooper()
                if (instance != null) {
                    eqStateInstance = instance
                    if (looper != null) eqObservationHandler = Handler(looper)
                    GmmpEqualizerRuntimeBridge.observeInstance(instance, looper)
                }
                val result = chain.proceed()
                if (instance != null) observeEqualizerBandCount(instance)

                when (role) {
                    EqualizerObservationRole.PRE -> (args.getOrNull(0) as? Number)?.toDouble()?.let { raw ->
                        currentEqPre = raw
                        if (instance != null) verifyPreReader(instance, raw)
                        NativeAudioObservation.equalizerPre(raw)?.let(::publishNativeValue)
                        updateCompleteShapeTimestamp()
                        maybeCompletePendingPresetFromObservedShape()
                    }

                    EqualizerObservationRole.BAND -> {
                        val index = (args.getOrNull(0) as? Number)?.toInt()
                        val raw = (args.getOrNull(1) as? Number)?.toDouble()
                        val count = activeEqualizerBandCount()
                        if (index != null && raw != null && count != null && index in 0 until count) {
                            currentEqBands[index] = raw
                            if (instance != null) verifyBandReader(instance, index, raw)
                            updateCompleteShapeTimestamp()
                            maybeCompletePendingPresetFromObservedShape()
                        }
                    }

                    EqualizerObservationRole.ENABLED -> (args.getOrNull(0) as? Boolean)?.let { enabled ->
                        publishNativeValue(NativeAudioObservation.Value("eq.enabled", enabled.toString()))
                    }
                }

                if (BuildConfig.DEBUG) {
                    Log.i(
                        PROBE_TAG,
                        "EQ CALL | role=$role | method=${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
                            " | args=${args.joinToString(",") { it?.toString() ?: "null" }}" +
                            " | caller=${caller ?: "unresolved"}"
                    )
                }
                result
            }
            true
        }.onFailure {
            Log.w(TAG, "Could not install passive EQ observer for role=$role", it)
        }.getOrDefault(false)

    private fun observeEqualizerBandCount(instance: Any): Int? {
        val field = eqBandArrayField ?: return GmmpEqualizerRuntimeBridge.current()?.bandCount
        val array = runCatching { field.get(instance) as? DoubleArray }.getOrNull() ?: return null
        val count = array.size.takeIf(GmmpEqualizerBandPolicy::isSupported) ?: return null
        if (observedEqBandCount != count) {
            observedEqBandCount = count
            currentEqPre = null
            currentEqBands.clear()
            verifiedBandReaderIndices.clear()
            lastCompleteShapeElapsed = 0L
            if (count != GmmpEqualizerBandPolicy.MAX_BANDS) {
                pendingPresetName = null
                pendingPresetUntilElapsed = 0L
            }
            Log.i(TAG, "EQ BAND LAYOUT | bands=$count | canonical=${GmmpEqualizerBandPolicy.MAX_BANDS}")
        }
        return count
    }

    private fun activeEqualizerBandCount(): Int? =
        observedEqBandCount ?: GmmpEqualizerRuntimeBridge.current()?.bandCount

    private fun verifyPreReader(instance: Any, raw: Double) {
        if (preReaderVerified) return
        val getter = eqPreGetter ?: return
        val value = runCatching { (getter.invoke(instance) as? Number)?.toDouble() }.getOrNull() ?: return
        if (value.isFinite() && abs(value - raw) <= READER_EPSILON) {
            preReaderVerified = true
            if (BuildConfig.DEBUG) Log.i(PROBE_TAG, "EQ READBACK VERIFIED | component=PRE")
        }
    }

    private fun verifyBandReader(instance: Any, index: Int, raw: Double) {
        val count = observeEqualizerBandCount(instance) ?: return
        if (index !in 0 until count) return
        val field = eqBandArrayField ?: return
        val array = runCatching { field.get(instance) as? DoubleArray }.getOrNull() ?: return
        if (array.size != count) return
        if (array[index].isFinite() && abs(array[index] - raw) <= READER_EPSILON) {
            verifiedBandReaderIndices += index
            if (BuildConfig.DEBUG && (0 until count).all { it in verifiedBandReaderIndices }) {
                Log.i(PROBE_TAG, "EQ READBACK VERIFIED | component=BANDS | count=$count")
            }
        }
    }

    private fun readCurrentEqualizerShape(): Map<String, String>? {
        val instance = eqStateInstance ?: return null
        val getter = eqPreGetter ?: return null
        val field = eqBandArrayField ?: return null
        val count = observeEqualizerBandCount(instance) ?: return null
        if (!preReaderVerified || !(0 until count).all { it in verifiedBandReaderIndices }) return null
        val pre = runCatching { (getter.invoke(instance) as? Number)?.toDouble() }.getOrNull() ?: return null
        val bands = runCatching { (field.get(instance) as? DoubleArray)?.copyOf() }.getOrNull() ?: return null
        if (bands.size != count) return null
        return canonicalEqualizerShape(pre, bands.toList())
    }

    private fun shapeFromObservedSetters(): Map<String, String>? {
        val pre = currentEqPre ?: return null
        val count = activeEqualizerBandCount() ?: return null
        val bands = (0 until count).map { index -> currentEqBands[index] ?: return null }
        return canonicalEqualizerShape(pre, bands)
    }

    private fun canonicalEqualizerShape(pre: Double, liveBands: List<Double>): Map<String, String>? {
        val canonicalBands = GmmpEqualizerBandPolicy.project(
            liveBands,
            GoneSmartAudioProfiles.equalizerBandFieldIds.size
        ) ?: return null
        val shape = linkedMapOf<String, String>()
        NativeAudioObservation.equalizerPre(pre)?.let { shape[it.fieldId] = it.value } ?: return null
        canonicalBands.forEachIndexed { index, raw ->
            NativeAudioObservation.equalizerBand(index, raw)?.let { shape[it.fieldId] = it.value } ?: return null
        }
        return shape.takeIf { it.size == GoneSmartAudioProfiles.equalizerShapeFieldIds.size }
    }

    private fun updateCompleteShapeTimestamp() {
        val shape = shapeFromObservedSetters() ?: return
        lastCompleteShapeElapsed = SystemClock.elapsedRealtime()
        shape.forEach { (fieldId, value) ->
            if (fieldId != "eq.pre_db") {
                publishNativeValue(NativeAudioObservation.Value(fieldId, value))
            }
        }
    }

    private fun discoverEqualizerCandidates(
        classLoader: ClassLoader,
        application: Application
    ): Sequence<Class<*>> = sequence {
        var inspected = 0
        for (path in applicationApkPaths(application)) {
            if (inspected >= MAX_DYNAMIC_EQ_CLASSES) break
            val dex = runCatching { DexFile(path) }.getOrNull() ?: continue
            try {
                val entries = dex.entries()
                while (entries.hasMoreElements() && inspected < MAX_DYNAMIC_EQ_CLASSES) {
                    val name = entries.nextElement()
                    if (!looksLikeGmmpImplementationClass(name)) continue
                    inspected += 1
                    val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
                    yield(clazz)
                }
            } finally {
                runCatching { dex.close() }
            }
        }
        if (BuildConfig.DEBUG) Log.i(PROBE_TAG, "EQ STRUCTURAL SCAN | inspected=$inspected")
    }

    private fun looksLikeGmmpImplementationClass(name: String): Boolean {
        if (name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("java.") ||
            name.startsWith("kotlin.") || name.startsWith("com.google.") || name.startsWith("io.github.alagga.")) {
            return false
        }
        if (name.startsWith("gonemad.gmmp.")) return true
        return '.' !in name && name.length in 2..10 && name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }

    private fun applicationApkPaths(application: Application): List<String> = buildList {
        add(application.applicationInfo.sourceDir)
        application.applicationInfo.splitSourceDirs?.let(::addAll)
    }.distinct()

    /** Import complete native preset curves from GMMP's own installed files. */
    private fun importNativePresetCatalog(application: Application) {
        if (!presetCatalogStarted.compareAndSet(false, true)) return
        Thread({
            val result = runCatching { GmmpEqualizerPresetCatalog.discover(application) }
                .onFailure { Log.w(TAG, "Native EQ preset catalog discovery failed", it) }
                .getOrNull()
                ?: return@Thread

            result.entries.forEach { entry ->
                publishNativePreset(entry.preset.name, entry.preset.shape, "catalog:${entry.source}")
            }
            Log.i(
                TAG,
                "EQ PRESET CATALOG | imported=${result.entries.size} | candidates=${result.candidateCount}" +
                    " | rejected=${result.rejectedCount}"
            )
        }, "GoneSmart-EqPresetCatalog").apply { isDaemon = true }.start()
    }

    /** Observe only already-mapped GMMP preference keys without changing them. */
    private fun installNativePreferenceObserver() {
        val editorClass = runCatching {
            Class.forName("android.app.SharedPreferencesImpl\$EditorImpl")
        }.getOrElse {
            Log.w(TAG, "SharedPreferences observer unavailable", it)
            return
        }

        val methodNames = setOf("putBoolean", "putFloat", "putInt", "putLong", "putString", "remove")
        editorClass.declaredMethods
            .filter { it.name in methodNames && it.parameterTypes.firstOrNull() == String::class.java }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    hook(method).intercept { chain ->
                        val key = chain.getArg(0) as? String
                        val value = if (method.name == "remove") null else runCatching { chain.getArg(1) }.getOrNull()
                        val caller = if (key == NativeAudioObservation.PREF_EQ_PRESET ||
                            (BuildConfig.DEBUG && key in NativeAudioObservation.preferenceKeys)
                        ) {
                            preferenceProbeCallSite()
                        } else {
                            null
                        }
                        val result = chain.proceed()

                        if (key != null && key in NativeAudioObservation.preferenceKeys) {
                            if (method.name == "remove") {
                                clearNativePreference(key)
                            } else {
                                observeNativePreference(key, value, "runtime", caller)
                            }
                            if (BuildConfig.DEBUG) maybeLogPreferenceMutation(method.name, key, value, caller)
                        }
                        result
                    }
                }.onFailure {
                    Log.w(TAG, "Could not install native preference observer for ${method.name}", it)
                }
            }
    }

    private fun observeNativePreference(
        key: String,
        rawValue: Any?,
        source: String,
        caller: String? = null
    ) {
        val mapped = NativeAudioObservation.fromPreference(key, rawValue) ?: return
        publishNativeValue(mapped)

        if (key == NativeAudioObservation.PREF_EQ_PRESET) {
            currentNativePreset = mapped.value
            if (mapped.value == GoneSmartAudioProfiles.CUSTOM_PRESET) {
                pendingPresetName = null
                pendingPresetUntilElapsed = 0L
                return
            }

            val naturalGmmpSelection = caller?.let(::isNaturalGmmpPresetWrite) == true
            if (!naturalGmmpSelection) {
                if (BuildConfig.DEBUG && source.startsWith("startup:")) {
                    Log.i(PROBE_TAG, "PRESET STARTUP LABEL | preset=${mapped.value} | learning=false")
                }
                return
            }

            // The installed preset catalog is authoritative ten-band reference
            // data. A natural preset action in a smaller live layout is useful
            // for playback observation but must not overwrite that exact curve
            // with an interpolated projection.
            val liveCount = activeEqualizerBandCount()
            if (liveCount != null && liveCount != GmmpEqualizerBandPolicy.MAX_BANDS) {
                pendingPresetName = null
                pendingPresetUntilElapsed = 0L
                if (BuildConfig.DEBUG) {
                    Log.i(
                        PROBE_TAG,
                        "PRESET NATURAL ACTION | preset=${mapped.value} | bands=$liveCount | learning=false"
                    )
                }
                return
            }

            val now = SystemClock.elapsedRealtime()
            val recentShape = shapeFromObservedSetters()?.takeIf {
                lastCompleteShapeElapsed > 0L && now - lastCompleteShapeElapsed <= PRESET_RECENT_SHAPE_MS
            }
            pendingPresetName = mapped.value
            pendingPresetUntilElapsed = now + PRESET_CAPTURE_WINDOW_MS

            if (recentShape != null) {
                publishNativePreset(mapped.value, recentShape, "natural-action-recent-setters")
            } else {
                // If the preset preference write precedes GMMP's setter burst,
                // collect a fresh complete shape and never mix with old bands.
                currentEqPre = null
                currentEqBands.clear()
                lastCompleteShapeElapsed = 0L
                schedulePresetReadback(mapped.value)
            }
        }
    }

    private fun isNaturalGmmpPresetWrite(caller: String): Boolean =
        caller.contains("rr3.setValue") && caller.contains("sa4.setValue")

    private fun schedulePresetReadback(preset: String) {
        val task = Runnable {
            if (pendingPresetName != preset || SystemClock.elapsedRealtime() > pendingPresetUntilElapsed) return@Runnable
            if (activeEqualizerBandCount() != GmmpEqualizerBandPolicy.MAX_BANDS) return@Runnable
            val shape = readCurrentEqualizerShape()
            if (shape == null) {
                if (BuildConfig.DEBUG) Log.i(PROBE_TAG, "PRESET READBACK DEFERRED | preset=$preset | verified=false")
                return@Runnable
            }
            currentEqPre = shape["eq.pre_db"]?.toDoubleOrNull()
            currentEqBands.clear()
            GoneSmartAudioProfiles.equalizerBandFieldIds.forEachIndexed { index, fieldId ->
                shape[fieldId]?.toDoubleOrNull()?.let { currentEqBands[index] = it }
            }
            lastCompleteShapeElapsed = SystemClock.elapsedRealtime()
            shape.forEach { (fieldId, value) ->
                publishNativeValue(NativeAudioObservation.Value(fieldId, value))
            }
            publishNativePreset(preset, shape, "validated-readback")
        }
        val handler = eqObservationHandler ?: mainHandler
        handler.postDelayed(task, PRESET_READBACK_DELAY_MS)
    }

    private fun clearNativePreference(key: String) {
        val mappedField = when (key) {
            NativeAudioObservation.PREF_EQ_ENABLED -> "eq.enabled"
            NativeAudioObservation.PREF_EQ_PRESET -> "eq.preset"
            "dspSettings_balanceEnabled" -> "balance.enabled"
            "dspSettings_balance" -> "balance.percent"
            "player_forceMono" -> "mono.enabled"
            "dspSettings_limiterEnabled" -> "limiter.enabled"
            "dspSettings_attack" -> "limiter.attack_ms"
            "dspSettings_release" -> "limiter.release_ms"
            "dspSettings_bassBoostEnabled" -> "bass.enabled"
            "dspSettings_bassBoostStrength" -> "bass.strength_percent"
            "player_tempoEnabled" -> "tempo.enabled"
            "player_tempo" -> "tempo.percent"
            "player_autoPitchCorrection" -> "pitch_correction.enabled"
            "dspSettings_virtualizerEnabled" -> "virtualizer.enabled"
            "dspSettings_virtualizerStrength" -> "virtualizer.strength_percent"
            "dspSettings_loudnessEnabled" -> "amplifier.enabled"
            "dspSettings_loudnessGain" -> "amplifier.gain_db"
            else -> null
        } ?: return
        publishedNativeValues.remove(mappedField)
        persistStandardRuntimeValue(mappedField, null)
        sendNativeValueToCompanion(mappedField, value = null, clear = true)
        if (mappedField == "eq.preset") {
            currentNativePreset = null
            pendingPresetName = null
            pendingPresetUntilElapsed = 0L
        }
    }

    private fun publishNativeValue(value: NativeAudioObservation.Value, force: Boolean = false) {
        val field = GoneSmartAudioProfiles.field(value.fieldId) ?: return
        val normalized = field.normalize(value.value) ?: return
        persistStandardRuntimeValue(value.fieldId, normalized)
        if (!force && publishedNativeValues[value.fieldId] == normalized) return
        if (sendNativeValueToCompanion(value.fieldId, normalized, clear = false)) {
            publishedNativeValues[value.fieldId] = normalized
            if (BuildConfig.DEBUG) {
                Log.i(
                    PROBE_TAG,
                    "NATIVE MIRROR | field=${value.fieldId} | value=$normalized | route=$activeRouteId" +
                        if (force) " | force=true" else ""
                )
            }
        }
    }

    private fun sendNativeValueToCompanion(fieldId: String, value: String?, clear: Boolean): Boolean {
        val application = hostApplication ?: currentApplication() ?: return false
        val extras = Bundle().apply {
            putString(AudioProfileEventReceiver.EXTRA_ROUTE_ID, activeRouteId)
            putString(AudioProfileEventReceiver.EXTRA_SETTING_ID, fieldId)
            putBoolean(AudioProfileEventReceiver.EXTRA_SETTING_CLEAR, clear)
            if (value != null) putString(AudioProfileEventReceiver.EXTRA_SETTING_VALUE, value)
        }
        if (AudioProfileCompanionBridge.report(
                application,
                AudioProfileCompanionBridge.METHOD_NATIVE_VALUE,
                extras
            )
        ) {
            logBridgeProviderVerified()
            return true
        }

        val intent = Intent(AudioProfileEventReceiver.ACTION_NATIVE_VALUE_OBSERVED).apply { putExtras(extras) }
        return sendFallbackBroadcast(application, intent, "native value $fieldId")
    }

    private fun maybeCompletePendingPresetFromObservedShape() {
        val preset = pendingPresetName ?: return
        if (activeEqualizerBandCount() != GmmpEqualizerBandPolicy.MAX_BANDS) return
        if (SystemClock.elapsedRealtime() > pendingPresetUntilElapsed) return
        val shape = shapeFromObservedSetters() ?: return
        lastCompleteShapeElapsed = SystemClock.elapsedRealtime()
        publishNativePreset(preset, shape, "observed-setters")
    }

    private fun publishNativePreset(preset: String, shape: Map<String, String>, source: String) {
        if (preset.isBlank() || preset == GoneSmartAudioProfiles.CUSTOM_PRESET) return
        if (shape.keys.toSet() != GoneSmartAudioProfiles.equalizerShapeFieldIds.toSet()) return

        val normalized = GoneSmartAudioProfiles.equalizerShapeFieldIds.associateWith { fieldId ->
            val field = GoneSmartAudioProfiles.field(fieldId) ?: return
            field.normalize(shape[fieldId] ?: return) ?: return
        }
        val fingerprint = GoneSmartAudioProfiles.equalizerShapeFieldIds
            .joinToString("|") { fieldId -> "$fieldId=${normalized[fieldId]}" }
        if (publishedPresetFingerprints[preset] == fingerprint) {
            pendingPresetName = null
            pendingPresetUntilElapsed = 0L
            return
        }

        persistRuntimePreset(preset, normalized)

        val application = hostApplication ?: currentApplication() ?: return
        val ids = ArrayList(GoneSmartAudioProfiles.equalizerShapeFieldIds)
        val values = ArrayList(ids.map { normalized[it] ?: return })
        val extras = Bundle().apply {
            putString(AudioProfileEventReceiver.EXTRA_PRESET_NAME, preset)
            putStringArrayList(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_IDS, ids)
            putStringArrayList(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_VALUES, values)
        }
        val bridged = AudioProfileCompanionBridge.report(
            application,
            AudioProfileCompanionBridge.METHOD_NATIVE_PRESET,
            extras
        )
        val sent = if (bridged) {
            logBridgeProviderVerified()
            true
        } else {
            val intent = Intent(AudioProfileEventReceiver.ACTION_NATIVE_PRESET_OBSERVED).apply { putExtras(extras) }
            sendFallbackBroadcast(application, intent, "native preset $preset")
        }
        if (!sent) return

        publishedPresetFingerprints[preset] = fingerprint
        pendingPresetName = null
        pendingPresetUntilElapsed = 0L
        if (BuildConfig.DEBUG) {
            Log.i(PROBE_TAG, "PRESET SNAPSHOT | preset=$preset | source=$source | fields=${normalized.size}")
        }
    }

    private fun persistStandardRuntimeValue(fieldId: String, value: String?) {
        if (!AudioProfileRuntimePolicy.isStandardRoute(activeRouteId)) return
        val application = hostApplication ?: currentApplication() ?: return
        val editor = application.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE).edit()
        val key = "$STANDARD_PREFIX$fieldId"
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private fun persistRuntimePreset(preset: String, shape: Map<String, String>) {
        val application = hostApplication ?: currentApplication() ?: return
        val prefs = application.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        if (!AudioProfilePresetStorage.ensureCurrent(prefs)) return
        val editor = prefs.edit()
        editor.putString(GoneSmartAudioProfiles.nativePresetNameKey(preset), preset)
        shape.forEach { (fieldId, value) ->
            editor.putString(GoneSmartAudioProfiles.nativePresetValueKey(preset, fieldId), value)
        }
        editor.apply()
    }

    private fun sendFallbackBroadcast(application: Application, intent: Intent, kind: String): Boolean =
        runCatching {
            intent.component = ComponentName(
                COMPANION_PACKAGE,
                "$COMPANION_PACKAGE.AudioProfileEventReceiver"
            )
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            application.sendBroadcast(intent)
            if (bridgeFallbackLogged.compareAndSet(false, true)) {
                Log.w(TAG, "COMPANION BRIDGE FALLBACK | provider unavailable | explicit receiver enabled")
            }
            true
        }.onFailure {
            Log.w(TAG, "Could not mirror $kind to companion", it)
        }.getOrDefault(false)

    private fun logBridgeProviderVerified() {
        if (bridgeProviderVerified.compareAndSet(false, true)) {
            Log.i(TAG, "COMPANION BRIDGE VERIFIED | transport=content-provider")
        }
    }

    private fun maybeLogPreferenceMutation(methodName: String, key: String, value: Any?, caller: String?) {
        if (SystemClock.elapsedRealtime() - preferenceProbeStartedAt > PREF_PROBE_WINDOW_MS) return
        if (preferenceProbeEvents.get() >= PREF_PROBE_MAX_EVENTS) return

        val rendered = when (value) {
            null -> "<removed>"
            is Boolean, is Int, is Long, is Float, is Double -> value.toString()
            else -> "<${value.javaClass.simpleName}>"
        }
        val fingerprint = "$methodName|$key|${value?.javaClass?.name ?: "none"}|${value?.hashCode() ?: 0}"
        if (!preferenceProbeSeen.add(fingerprint)) return
        if (preferenceProbeEvents.incrementAndGet() > PREF_PROBE_MAX_EVENTS) return

        Log.i(
            PROBE_TAG,
            "PREF WRITE | op=$methodName | key=$key | value=$rendered" +
                " | type=${value?.javaClass?.name ?: "none"}" +
                " | caller=${caller ?: "unresolved"}"
        )
    }

    private fun preferenceProbeCallSite(): String =
        Thread.currentThread().stackTrace
            .asSequence()
            .filterNot { frame ->
                frame.className == Thread::class.java.name ||
                    frame.className.startsWith("io.github.alagga.gonesmart.") ||
                    frame.className.startsWith("io.github.libxposed.") ||
                    frame.className.startsWith("android.") ||
                    frame.className.startsWith("java.") ||
                    frame.className.startsWith("kotlin.") ||
                    frame.className.startsWith("dalvik.")
            }
            .take(8)
            .joinToString(" <- ") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
            .ifBlank { "unresolved" }
}
