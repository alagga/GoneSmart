package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import dalvik.system.DexFile
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent feature family for output-specific GMMP audio profiles.
 *
 * GMMP's native state is observed read-only. The GMMP-side libxposed
 * RemotePreferences implementation is intentionally treated as read-only too:
 * observations are sent to the GoneSmart companion process, which owns the
 * writable settings mirror. Actual profile application remains deferred until
 * GMMP's native mutation/reload boundary is proven.
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
        private const val MAX_DYNAMIC_EQ_CLASSES = 12_000
    }

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

    private val currentEqBands = ConcurrentHashMap<Int, Double>()
    private val publishedNativeValues = ConcurrentHashMap<String, String>()
    private val publishedPresetFingerprints = ConcurrentHashMap<String, String>()
    private val preferenceProbeStartedAt = SystemClock.elapsedRealtime()
    private val preferenceProbeEvents = AtomicInteger(0)
    private val preferenceProbeSeen = ConcurrentHashMap.newKeySet<String>()
    private val presetCatalogStarted = AtomicBoolean(false)

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

        // Preference observation is keyed by semantic GMMP setting IDs and is
        // independent of obfuscated GMMP implementation class names.
        installNativePreferenceObserver()
        resolveApplicationAndStart(param.classLoader, attempt = 0)
    }

    private fun resolveApplicationAndStart(classLoader: ClassLoader, attempt: Int) {
        val application = currentApplication()
        if (application != null) {
            hostApplication = application

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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
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
        val intent = Intent(AudioProfileEventReceiver.ACTION_ROUTE_OBSERVED)
            .setPackage(COMPANION_PACKAGE)
            .putExtra(AudioProfileEventReceiver.EXTRA_ROUTE_ID, route.id)
            .putExtra(AudioProfileEventReceiver.EXTRA_ROUTE_LABEL, route.label)
            .putExtra(AudioProfileEventReceiver.EXTRA_ROUTE_BLUETOOTH, route.bluetooth)
            .putExtra(AudioProfileEventReceiver.EXTRA_LAST_SEEN, route.lastSeenEpochMs)
        runCatching { application.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "Could not report active audio route to companion", it) }
        logActiveOverrideState("route changed")
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
            Log.w(
                TAG,
                "PROFILE APPLY DEFERRED | route=$activeRouteId | overrides=${overrides.size}" +
                    " | native GMMP DSP mutation boundary is not proven yet"
            )
        }
    }

    /** One bounded startup read of GMMP SharedPreferences. */
    private fun snapshotNativePreferences(application: Application) {
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

                if (BuildConfig.DEBUG) {
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
     * Resolve the Equalizer observation owner semantically. The 4.2.1 R8 name
     * is only a structurally validated fast path; if it changes, a bounded
     * startup-only Dex scan looks for one unique class with the same semantic
     * state shape. GoneSmart only hooks natural calls and never invokes a
     * candidate method to test it.
     */
    private fun installEqualizerObserver(classLoader: ClassLoader, application: Application): Boolean {
        val fast = GmmpEqualizerObservationResolver.resolveFastPath(classLoader)
        val mapping = fast ?: GmmpEqualizerObservationResolver.resolveDynamic(
            discoverEqualizerCandidates(classLoader, application)
        )
        if (mapping == null) {
            Log.w(TAG, "EQ state observer unavailable; structural resolver found no unique candidate")
            return false
        }

        Log.i(
            TAG,
            "EQ OBSERVER RESOLVED | source=${mapping.source} | owner=${mapping.owner.name} | score=${mapping.score}"
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
                val result = chain.proceed()

                when (role) {
                    EqualizerObservationRole.PRE -> (args.getOrNull(0) as? Number)?.toDouble()?.let { raw ->
                        currentEqPre = raw
                        NativeAudioObservation.equalizerPre(raw)?.let(::publishNativeValue)
                        maybeCompletePendingPresetFromObservedShape()
                    }

                    EqualizerObservationRole.BAND -> {
                        val index = (args.getOrNull(0) as? Number)?.toInt()
                        val raw = (args.getOrNull(1) as? Number)?.toDouble()
                        if (index != null && raw != null) {
                            currentEqBands[index] = raw
                            NativeAudioObservation.equalizerBand(index, raw)?.let(::publishNativeValue)
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
        // R8 implementation classes in the accepted build live in the default
        // package with short generated names (for example the 4.2.1 fast path).
        return '.' !in name && name.length in 2..10 && name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    }

    private fun applicationApkPaths(application: Application): List<String> = buildList {
        add(application.applicationInfo.sourceDir)
        application.applicationInfo.splitSourceDirs?.let(::addAll)
    }.distinct()

    /**
     * Import complete native preset curves from GMMP's own installed preset
     * files. This is read-only, bounded and startup-only; passive runtime
     * observation remains a fallback for formats that cannot be parsed safely.
     */
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

    /**
     * Observe only already-mapped GMMP preference keys. This hook never changes
     * GMMP's editor call; it mirrors the value to the companion after the native
     * call has proceeded.
     */
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
                        val caller = if (BuildConfig.DEBUG && key in NativeAudioObservation.preferenceKeys) {
                            preferenceProbeCallSite()
                        } else {
                            null
                        }
                        val result = chain.proceed()

                        if (key != null && key in NativeAudioObservation.preferenceKeys) {
                            if (method.name == "remove") {
                                clearNativePreference(key)
                            } else {
                                observeNativePreference(key, value, "runtime")
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

    private fun observeNativePreference(key: String, rawValue: Any?, source: String) {
        val mapped = NativeAudioObservation.fromPreference(key, rawValue) ?: return
        publishNativeValue(mapped)

        if (key == NativeAudioObservation.PREF_EQ_PRESET) {
            currentNativePreset = mapped.value
            if (mapped.value == GoneSmartAudioProfiles.CUSTOM_PRESET) {
                pendingPresetName = null
                pendingPresetUntilElapsed = 0L
            } else {
                val startupSnapshot = source.startsWith("startup:")
                if (!startupSnapshot) {
                    currentEqPre = null
                    currentEqBands.clear()
                }
                pendingPresetName = mapped.value
                pendingPresetUntilElapsed = SystemClock.elapsedRealtime() + PRESET_CAPTURE_WINDOW_MS
                if (startupSnapshot) maybeCompletePendingPresetFromObservedShape()
            }
        }
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
        sendNativeValueBroadcast(mappedField, value = null, clear = true)
        if (mappedField == "eq.preset") {
            currentNativePreset = null
            pendingPresetName = null
            pendingPresetUntilElapsed = 0L
        }
    }

    private fun publishNativeValue(value: NativeAudioObservation.Value) {
        val field = GoneSmartAudioProfiles.field(value.fieldId) ?: return
        val normalized = field.normalize(value.value) ?: return
        if (publishedNativeValues[value.fieldId] == normalized) return
        if (sendNativeValueBroadcast(value.fieldId, normalized, clear = false)) {
            publishedNativeValues[value.fieldId] = normalized
            if (BuildConfig.DEBUG) {
                Log.i(
                    PROBE_TAG,
                    "NATIVE MIRROR | field=${value.fieldId} | value=$normalized | route=$activeRouteId"
                )
            }
        }
    }

    private fun sendNativeValueBroadcast(fieldId: String, value: String?, clear: Boolean): Boolean {
        val application = hostApplication ?: currentApplication() ?: return false
        val intent = Intent(AudioProfileEventReceiver.ACTION_NATIVE_VALUE_OBSERVED)
            .setPackage(COMPANION_PACKAGE)
            .putExtra(AudioProfileEventReceiver.EXTRA_ROUTE_ID, activeRouteId)
            .putExtra(AudioProfileEventReceiver.EXTRA_SETTING_ID, fieldId)
            .putExtra(AudioProfileEventReceiver.EXTRA_SETTING_CLEAR, clear)
        if (value != null) intent.putExtra(AudioProfileEventReceiver.EXTRA_SETTING_VALUE, value)
        return runCatching {
            application.sendBroadcast(intent)
            true
        }.onFailure {
            Log.w(TAG, "Could not mirror native GMMP value '$fieldId' to companion", it)
        }.getOrDefault(false)
    }

    private fun maybeCompletePendingPresetFromObservedShape() {
        val preset = pendingPresetName ?: return
        if (SystemClock.elapsedRealtime() > pendingPresetUntilElapsed) return
        val pre = currentEqPre ?: return
        val bands = (0 until GoneSmartAudioProfiles.equalizerBandFieldIds.size).map { index ->
            currentEqBands[index] ?: return
        }
        val shape = linkedMapOf<String, String>()
        NativeAudioObservation.equalizerPre(pre)?.let { shape[it.fieldId] = it.value } ?: return
        bands.forEachIndexed { index, raw ->
            NativeAudioObservation.equalizerBand(index, raw)?.let { shape[it.fieldId] = it.value } ?: return
        }
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

        val application = hostApplication ?: currentApplication() ?: return
        val ids = ArrayList(GoneSmartAudioProfiles.equalizerShapeFieldIds)
        val values = ArrayList(ids.map { normalized[it] ?: return })
        val intent = Intent(AudioProfileEventReceiver.ACTION_NATIVE_PRESET_OBSERVED)
            .setPackage(COMPANION_PACKAGE)
            .putExtra(AudioProfileEventReceiver.EXTRA_PRESET_NAME, preset)
            .putStringArrayListExtra(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_IDS, ids)
            .putStringArrayListExtra(AudioProfileEventReceiver.EXTRA_PRESET_FIELD_VALUES, values)
        val sent = runCatching {
            application.sendBroadcast(intent)
            true
        }.onFailure {
            Log.w(TAG, "Could not mirror native GMMP preset '$preset' to companion", it)
        }.getOrDefault(false)
        if (!sent) return

        publishedPresetFingerprints[preset] = fingerprint
        pendingPresetName = null
        pendingPresetUntilElapsed = 0L
        if (BuildConfig.DEBUG) {
            Log.i(PROBE_TAG, "PRESET SNAPSHOT | preset=$preset | source=$source | fields=${normalized.size}")
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
