package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
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
        private const val PRESET_SNAPSHOT_DELAY_MS = 850L
    }

    private var audioPreferences: SharedPreferences? = null
    private var activeRouteId: String = GoneSmartAudioProfiles.LOCAL_ROUTE_ID
    private var routeObserver: AudioRouteObserver? = null
    private var nativePreferenceSnapshotDone = false

    @Volatile
    private var hostApplication: Application? = null

    @Volatile
    private var eqStateInstance: Any? = null

    @Volatile
    private var currentNativePreset: String? = null

    @Volatile
    private var currentEqPre: Double? = null

    @Volatile
    private var pendingPresetName: String? = null

    @Volatile
    private var pendingPresetUntilElapsed: Long = 0L

    @Volatile
    private var preGetterVerified = false

    @Volatile
    private var verifiedBandGainGetterName: String? = null

    private val currentEqBands = ConcurrentHashMap<Int, Double>()
    private val publishedNativeValues = ConcurrentHashMap<String, String>()
    private val publishedPresetFingerprints = ConcurrentHashMap<String, String>()
    private val preferenceProbeStartedAt = SystemClock.elapsedRealtime()
    private val preferenceProbeEvents = AtomicInteger(0)
    private val preferenceProbeSeen = ConcurrentHashMap.newKeySet<String>()

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

        // Both observers are transparent: they never change GMMP arguments or
        // return values and never write into GMMP/libxposed preferences.
        installEqualizerObserver(param.classLoader)
        installNativePreferenceObserver()
        resolveApplicationAndStart(attempt = 0)

        if (BuildConfig.DEBUG) {
            logNativeDspInventory(param.classLoader)
        }
    }

    private fun resolveApplicationAndStart(attempt: Int) {
        val application = currentApplication()
        if (application != null) {
            hostApplication = application

            // Resolve the route first so every startup native observation is
            // explicitly attributed to Standard or the active Bluetooth output.
            if (routeObserver == null) {
                routeObserver = AudioRouteObserver(application) { route -> onRouteChanged(application, route) }
                routeObserver?.start()
            }

            if (!nativePreferenceSnapshotDone) {
                nativePreferenceSnapshotDone = true
                snapshotNativePreferences(application)
            }
            if (eqStateInstance != null) {
                scheduleEqualizerSnapshot("application-ready")
            }
            return
        }

        if (attempt >= CONTEXT_MAX_ATTEMPTS) {
            Log.w(TAG, "Could not obtain GMMP application context; audio-route observation unavailable")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { resolveApplicationAndStart(attempt + 1) },
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
     * Passive runtime observation of the proven GMMP 4.2.1 EQ state object.
     * r(double) is PRE, z(index,double) is band gain, and d(boolean) is the
     * enabled state as established by device evidence. The hook is transparent.
     */
    private fun installEqualizerObserver(classLoader: ClassLoader) {
        val eqClass = runCatching { classLoader.loadClass("r61") }.getOrElse {
            Log.w(TAG, "EQ state observer unavailable", it)
            return
        }

        eqClass.declaredMethods
            .filter { method ->
                when (method.name) {
                    "z" -> method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Double::class.javaPrimitiveType))
                    "r" -> method.parameterTypes.contentEquals(arrayOf(Double::class.javaPrimitiveType))
                    "d" -> method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                    "reset", "t" -> method.parameterCount == 0
                    else -> false
                }
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    hook(method).intercept { chain ->
                        eqStateInstance = chain.thisObject
                        val args = method.parameterTypes.indices.map { index ->
                            runCatching { chain.getArg(index) }.getOrNull()
                        }
                        val caller = if (BuildConfig.DEBUG) preferenceProbeCallSite() else null
                        val result = chain.proceed()

                        when (method.name) {
                            "r" -> (args.getOrNull(0) as? Number)?.toDouble()?.let { raw ->
                                currentEqPre = raw
                                NativeAudioObservation.equalizerPre(raw)?.let(::publishNativeValue)
                                maybeCompletePendingPresetFromObservedShape()
                            }

                            "z" -> {
                                val index = (args.getOrNull(0) as? Number)?.toInt()
                                val raw = (args.getOrNull(1) as? Number)?.toDouble()
                                if (index != null && raw != null) {
                                    currentEqBands[index] = raw
                                    NativeAudioObservation.equalizerBand(index, raw)?.let(::publishNativeValue)
                                    maybeCompletePendingPresetFromObservedShape()
                                }
                            }

                            "d" -> (args.getOrNull(0) as? Boolean)?.let { enabled ->
                                publishNativeValue(NativeAudioObservation.Value("eq.enabled", enabled.toString()))
                            }

                            "reset", "t" -> scheduleEqualizerSnapshot("${method.name}()")
                        }

                        if (BuildConfig.DEBUG) {
                            Log.i(
                                PROBE_TAG,
                                "EQ CALL | method=${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
                                    " | args=${args.joinToString(",") { it?.toString() ?: "null" }}" +
                                    " | caller=${caller ?: "unresolved"}"
                            )
                        }
                        result
                    }
                }.onFailure {
                    Log.w(TAG, "Could not install EQ observer for ${method.name}", it)
                }
            }
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
                // Startup may race with the natural r()/z() initialization. Keep
                // any already observed shape so it can prove the initial preset.
                // A real runtime preset change, however, must discard the old
                // shape before learning the new one.
                val startupSnapshot = source.startsWith("startup:")
                if (!startupSnapshot) {
                    currentEqPre = null
                    currentEqBands.clear()
                }
                pendingPresetName = mapped.value
                pendingPresetUntilElapsed = SystemClock.elapsedRealtime() + PRESET_CAPTURE_WINDOW_MS
                if (startupSnapshot) maybeCompletePendingPresetFromObservedShape()
                scheduleEqualizerSnapshot("preset:${mapped.value}:$source")
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
        publishNativePreset(preset, shape, "observed setters")
        // The proven setter shape is also the evidence needed to graduate the
        // compact read-only getters used for later preset-only transitions.
        scheduleEqualizerSnapshot("observed-shape:$preset")
    }

    private fun scheduleEqualizerSnapshot(reason: String) {
        Handler(Looper.getMainLooper()).postDelayed(
            { snapshotEqualizerState(reason) },
            PRESET_SNAPSHOT_DELAY_MS
        )
    }

    /**
     * Bounded read-only verification for the observed 4.2.1 r61 getter candidates.
     * F() is accepted as PRE only after agreeing with r(double); one of K/N is
     * accepted as the band-gain getter only by range/agreement with observed z().
     */
    private fun snapshotEqualizerState(reason: String) {
        val instance = eqStateInstance ?: return
        val clazz = instance.javaClass
        val countMethod = findMethod(clazz, "D") ?: return
        val preMethod = findMethod(clazz, "F") ?: return
        val kMethod = findMethod(clazz, "K", Int::class.javaPrimitiveType!!) ?: return
        val nMethod = findMethod(clazz, "N", Int::class.javaPrimitiveType!!) ?: return

        val count = runCatching { (countMethod.invoke(instance) as? Number)?.toInt() }.getOrNull() ?: return
        if (count != GoneSmartAudioProfiles.equalizerBandFieldIds.size) {
            if (BuildConfig.DEBUG) Log.w(PROBE_TAG, "EQ SNAPSHOT skipped | bands=$count | reason=$reason")
            return
        }

        val pre = runCatching { (preMethod.invoke(instance) as? Number)?.toDouble() }.getOrNull()
            ?.takeIf { it.isFinite() && it in -12.001..12.001 }
            ?: return
        if (!preGetterVerified) {
            val observedPre = currentEqPre ?: return
            if (kotlin.math.abs(pre - observedPre) > 0.001) {
                if (BuildConfig.DEBUG) {
                    Log.w(PROBE_TAG, "EQ SNAPSHOT rejected F() as PRE getter | returned=$pre | observed=$observedPre")
                }
                return
            }
            preGetterVerified = true
        }

        val kValues = readIndexedDoubles(kMethod, instance, count) ?: return
        val nValues = readIndexedDoubles(nMethod, instance, count) ?: return
        val gains = selectGainGetter(kValues, nValues) ?: return

        currentEqPre = pre
        gains.forEachIndexed { index, value -> currentEqBands[index] = value }
        NativeAudioObservation.equalizerPre(pre)?.let(::publishNativeValue)
        gains.forEachIndexed { index, value ->
            NativeAudioObservation.equalizerBand(index, value)?.let(::publishNativeValue)
        }

        val preset = currentNativePreset
        if (!preset.isNullOrBlank() && preset != GoneSmartAudioProfiles.CUSTOM_PRESET) {
            val shape = linkedMapOf<String, String>()
            NativeAudioObservation.equalizerPre(pre)?.let { shape[it.fieldId] = it.value }
            gains.forEachIndexed { index, value ->
                NativeAudioObservation.equalizerBand(index, value)?.let { shape[it.fieldId] = it.value }
            }
            if (shape.size == GoneSmartAudioProfiles.equalizerShapeFieldIds.size) {
                publishNativePreset(preset, shape, "getter snapshot:$reason")
            }
        }

        if (BuildConfig.DEBUG) {
            Log.i(
                PROBE_TAG,
                "EQ SNAPSHOT | preset=${preset ?: "unknown"} | pre=${AudioProfileField.formatNumber(pre)}" +
                    " | bands=${gains.joinToString(",") { AudioProfileField.formatNumber(it) }} | reason=$reason"
            )
        }
    }

    private fun selectGainGetter(kValues: List<Double>, nValues: List<Double>): List<Double>? {
        fun plausible(values: List<Double>): Boolean = values.all { it.isFinite() && it in -12.001..12.001 }
        val verified = verifiedBandGainGetterName
        if (verified == "K") return kValues.takeIf(::plausible)
        if (verified == "N") return nValues.takeIf(::plausible)

        val kPlausible = plausible(kValues)
        val nPlausible = plausible(nValues)
        if (kPlausible && !nPlausible) {
            verifiedBandGainGetterName = "K"
            return kValues
        }
        if (nPlausible && !kPlausible) {
            verifiedBandGainGetterName = "N"
            return nValues
        }
        if (!kPlausible && !nPlausible) return null

        val observed = currentEqBands.toMap()
        if (observed.isEmpty()) return null
        fun error(values: List<Double>): Double = observed.entries
            .filter { it.key in values.indices }
            .sumOf { (index, expected) -> kotlin.math.abs(values[index] - expected) }
        val kError = error(kValues)
        val nError = error(nValues)
        val selected = if (kError <= nError) "K" else "N"
        val selectedError = minOf(kError, nError)
        if (selectedError > 0.01 * observed.size) return null
        verifiedBandGainGetterName = selected
        return if (selected == "K") kValues else nValues
    }

    private fun readIndexedDoubles(method: Method, instance: Any, count: Int): List<Double>? =
        (0 until count).map { index ->
            runCatching { (method.invoke(instance, index) as? Number)?.toDouble() }.getOrNull()
                ?.takeIf { it.isFinite() }
                ?: return null
        }

    private fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching {
            clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        }.getOrNull()

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

    /** Debug-only class inventory retained only while the native apply path is unresolved. */
    private fun logNativeDspInventory(classLoader: ClassLoader) {
        listOf("r61", "s61", "gb1").forEach { candidateName ->
            val candidate = runCatching { classLoader.loadClass(candidateName) }.getOrNull() ?: return@forEach
            val constructors = candidate.declaredConstructors
                .map { constructor ->
                    candidate.simpleName + "(" + constructor.parameterTypes.joinToString(",") { it.name } + ")"
                }
                .ifEmpty { listOf("none") }
            Log.i(PROBE_TAG, "DSP CLASS | class=${candidate.name} | constructors=${constructors.joinToString(",")}")

            candidate.declaredFields
                .sortedBy { it.name }
                .map { field ->
                    "${if (Modifier.isStatic(field.modifiers)) "static " else ""}${field.name}:${field.type.name}"
                }
                .chunked(12)
                .forEachIndexed { index, chunk ->
                    Log.i(PROBE_TAG, "DSP FIELDS | class=${candidate.name} | chunk=${index + 1} | ${chunk.joinToString(",")}")
                }

            candidate.declaredMethods
                .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
                .map { method ->
                    method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + "):" + method.returnType.name
                }
                .chunked(12)
                .forEachIndexed { index, chunk ->
                    Log.i(PROBE_TAG, "DSP METHODS | class=${candidate.name} | chunk=${index + 1} | ${chunk.joinToString(",")}")
                }
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
