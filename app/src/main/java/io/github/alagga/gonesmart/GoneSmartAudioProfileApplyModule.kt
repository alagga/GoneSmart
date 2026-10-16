package io.github.alagga.gonesmart

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies the active GoneSmart audio profile through GMMP's own proven state
 * boundaries. Effects and Equalizer metadata use the same native GMMP
 * SharedPreferences keys written by GMMP's UI. PRE/bands/enabled use the
 * semantically resolved live Equalizer setters proved by natural GMMP calls.
 *
 * No DSP implementation is duplicated here. If a native boundary or the
 * Standard baseline is incomplete, that part of the profile fails closed.
 */
class GoneSmartAudioProfileApplyModule : XposedModule() {

    companion object {
        private const val TAG = "GoneSmartAudioApply"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val CONTEXT_RETRY_MS = 250L
        private const val CONTEXT_MAX_ATTEMPTS = 40
        private const val APPLY_DEBOUNCE_MS = 80L
        private const val MIN_NATIVE_PREF_MATCHES = 6
        private const val RUNTIME_PREFS = "gonesmart_audio_runtime_state"
        private const val KEY_OVERLAY_ACTIVE = "overlay_active"
        private const val KEY_LAST_ROUTE = "last_route"
        private const val STANDARD_PREFIX = "standard."
        private const val NATIVE_PRESET_PREFIX = "native_preset."
        private const val VALUE_PREFIX = "value."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val applyPending = AtomicBoolean(false)
    private val runtimeStandardFallbackLogged = AtomicBoolean(false)
    private val eqReadyListenerRegistered = AtomicBoolean(false)

    @Volatile
    private var pendingReason: String = "startup"

    @Volatile
    private var profilePreferences: SharedPreferences? = null

    @Volatile
    private var nativePreferences: SharedPreferences? = null

    @Volatile
    private var runtimePreferences: SharedPreferences? = null

    @Volatile
    private var activeRouteId: String = GoneSmartAudioProfiles.LOCAL_ROUTE_ID

    @Volatile
    private var lastObservedRouteId: String? = null

    private var routeObserver: AudioRouteObserver? = null

    @Volatile
    private var lastAppliedEqFingerprint: String? = null

    private val profilePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key.startsWith(VALUE_PREFIX) ||
            key.startsWith("override.") ||
            key.startsWith(STANDARD_PREFIX) ||
            key.startsWith(NATIVE_PRESET_PREFIX)
        ) {
            scheduleApply("profile preference changed")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != GMMP_PACKAGE) return

        profilePreferences = runCatching {
            getRemotePreferences(GoneSmartAudioProfiles.GROUP).also {
                it.registerOnSharedPreferenceChangeListener(profilePreferenceListener)
            }
        }.onFailure {
            Log.w(TAG, "Audio-profile preferences unavailable; native application disabled", it)
        }.getOrNull()

        resolveApplicationAndStart(attempt = 0)
    }

    private fun resolveApplicationAndStart(attempt: Int) {
        val application = currentApplication()
        if (application != null) {
            runtimePreferences = application.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            nativePreferences = resolveNativePreferenceStore(application)

            if (eqReadyListenerRegistered.compareAndSet(false, true)) {
                GmmpEqualizerRuntimeBridge.addReadyListener {
                    lastAppliedEqFingerprint = null
                    Log.i(TAG, "EQ APPLY TARGET READY | source=shared-observer-bridge")
                    scheduleApply("shared live Equalizer state ready")
                }
            }

            if (routeObserver == null) {
                routeObserver = AudioRouteObserver(application) { route -> onRouteChanged(route) }
                routeObserver?.start()
            }
            return
        }

        if (attempt >= CONTEXT_MAX_ATTEMPTS) {
            Log.w(TAG, "Could not obtain GMMP application context; audio-profile application unavailable")
            return
        }
        mainHandler.postDelayed(
            { resolveApplicationAndStart(attempt + 1) },
            CONTEXT_RETRY_MS
        )
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication").apply { isAccessible = true }
        method.invoke(null) as? Application
    }.getOrNull()

    /** Resolve GMMP's settings store from the semantic audio keys it contains. */
    private fun resolveNativePreferenceStore(application: Application): SharedPreferences? {
        val directory = File(application.applicationInfo.dataDir, "shared_prefs")
        val candidates = directory.listFiles { file -> file.isFile && file.name.endsWith(".xml") }
            .orEmpty()
            .mapNotNull { file ->
                val name = file.name.removeSuffix(".xml")
                if (name == RUNTIME_PREFS) return@mapNotNull null
                val prefs = runCatching {
                    application.getSharedPreferences(name, Context.MODE_PRIVATE)
                }.getOrNull() ?: return@mapNotNull null
                val score = NativeAudioObservation.preferenceKeys.count(prefs::contains)
                Triple(name, prefs, score)
            }
            .filter { it.third >= MIN_NATIVE_PREF_MATCHES }

        if (candidates.isEmpty()) {
            Log.w(TAG, "Native GMMP audio preference store unresolved; no semantic candidate")
            return null
        }
        val bestScore = candidates.maxOf { it.third }
        val best = candidates.filter { it.third == bestScore }
        if (best.size != 1) {
            Log.w(TAG, "Native GMMP audio preference store ambiguous; candidates=${best.size} score=$bestScore")
            return null
        }

        Log.i(TAG, "NATIVE PREFS RESOLVED | store=${best.single().first} | matchedKeys=$bestScore")
        return best.single().second
    }

    private fun onRouteChanged(route: AudioRouteProfile) {
        val previous = lastObservedRouteId
        lastObservedRouteId = route.id
        activeRouteId = route.id
        lastAppliedEqFingerprint = null

        val firstObservation = previous == null
        val standard = AudioProfileRuntimePolicy.isStandardRoute(route.id)
        val interruptedOverlay = runtimePreferences?.getBoolean(KEY_OVERLAY_ACTIVE, false) == true

        if (firstObservation && standard && !interruptedOverlay) {
            Log.i(TAG, "PROFILE APPLY SKIP | route=${route.id} | initial Standard is authoritative")
            return
        }

        scheduleApply(
            when {
                firstObservation && standard && interruptedOverlay -> "restore interrupted Bluetooth overlay"
                firstObservation -> "initial Bluetooth route"
                else -> "route changed from ${previous ?: "unknown"}"
            }
        )
    }

    private fun scheduleApply(reason: String) {
        pendingReason = reason
        if (!applyPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            applyPending.set(false)
            applyProfile(pendingReason)
        }, APPLY_DEBOUNCE_MS)
    }

    private fun applyProfile(reason: String) {
        val profiles = profilePreferences ?: return
        val native = nativePreferences ?: return
        val runtime = runtimePreferences ?: return
        val routeId = activeRouteId
        val standardRoute = AudioProfileRuntimePolicy.isStandardRoute(routeId)

        // A Bluetooth overlay is meaningful only relative to a complete normal
        // GMMP baseline. Never mutate GMMP while that baseline is incomplete;
        // the observation module captures it only while Standard is active.
        if (!hasCompleteStandard(profiles)) {
            Log.w(
                TAG,
                "PROFILE APPLY BLOCKED | route=$routeId | reason=$reason | Standard baseline incomplete"
            )
            return
        }

        val missingGroups = mutableListOf<String>()
        val writes = mutableListOf<NativeAudioMutation.PreferenceWrite>()

        GoneSmartAudioProfiles.effectGroups.forEach { group ->
            val values = resolveGroupValues(profiles, routeId, group)
            if (values == null) {
                missingGroups += group.id
                return@forEach
            }
            val groupWrites = group.fields.mapNotNull { field ->
                NativeAudioMutation.preferenceWrite(field.id, values[field.id] ?: return@mapNotNull null)
            }
            if (groupWrites.size != group.fields.size) {
                missingGroups += group.id
            } else {
                writes += groupWrites
            }
        }

        val eqTarget = resolveEqualizerTarget(profiles, routeId)
        val eqReady = eqTarget?.shape != null
        if (!eqReady) {
            missingGroups += GoneSmartAudioProfiles.equalizerGroup.id
        } else if (eqTarget != null) {
            NativeAudioMutation.preferenceWrite("eq.enabled", eqTarget.enabled)?.let(writes::add)
            if (eqTarget.preset != GoneSmartAudioProfiles.CUSTOM_PRESET) {
                NativeAudioMutation.preferenceWrite("eq.preset", eqTarget.preset)?.let(writes::add)
            }
        }

        val prefsVerified = writeNativePreferences(native, writes)
        val eqState = when {
            eqTarget == null -> "missing"
            eqTarget.shape == null -> "missing-shape"
            applyEqualizerShape(routeId, eqTarget) -> "native-shape"
            else -> "shape-deferred"
        }

        if (!standardRoute) {
            runtime.edit()
                .putBoolean(KEY_OVERLAY_ACTIVE, true)
                .putString(KEY_LAST_ROUTE, routeId)
                .apply()
        } else {
            val complete = missingGroups.isEmpty() && prefsVerified && eqState == "native-shape"
            if (complete) {
                runtime.edit()
                    .putBoolean(KEY_OVERLAY_ACTIVE, false)
                    .putString(KEY_LAST_ROUTE, routeId)
                    .apply()
            }
        }

        Log.i(
            TAG,
            "PROFILE APPLY | route=$routeId | reason=$reason | nativeWrites=${writes.size}" +
                " | prefsVerified=$prefsVerified | eq=$eqState" +
                if (missingGroups.isEmpty()) "" else " | missing=${missingGroups.distinct().joinToString(",")}"
        )
    }

    private fun hasCompleteStandard(prefs: SharedPreferences): Boolean =
        GoneSmartAudioProfiles.allFields.all { field -> standardValue(prefs, field.id) != null }

    private fun standardValue(prefs: SharedPreferences, fieldId: String): String? {
        val frameworkValue = prefs.getString("$STANDARD_PREFIX$fieldId", null)
        val runtimeValue = runtimePreferences?.getString("$STANDARD_PREFIX$fieldId", null)
        val resolved = AudioProfileValueResolver.resolve(fieldId, frameworkValue, runtimeValue)
        if (resolved != null && frameworkValue == null && runtimeValue != null &&
            runtimeStandardFallbackLogged.compareAndSet(false, true)
        ) {
            Log.i(TAG, "STANDARD RUNTIME FALLBACK ACTIVE | local GMMP baseline used")
        }
        return resolved
    }

    private fun resolveGroupValues(
        prefs: SharedPreferences,
        routeId: String,
        group: AudioProfileGroup
    ): Map<String, String>? {
        val useRouteOverride = !AudioProfileRuntimePolicy.isStandardRoute(routeId) &&
            prefs.getBoolean(GoneSmartAudioProfiles.overrideKey(routeId, group.id), false)

        val values = linkedMapOf<String, String>()
        group.fields.forEach { field ->
            val normalized = if (useRouteOverride) {
                prefs.getString(GoneSmartAudioProfiles.valueKey(routeId, field.id), null)
                    ?.let(field::normalize)
            } else {
                standardValue(prefs, field.id)
            } ?: return null
            values[field.id] = normalized
        }
        return values
    }

    private data class EqualizerTarget(
        val enabled: String,
        val preset: String,
        val shape: Map<String, String>?,
        val shapeSource: String
    )

    private fun resolveEqualizerTarget(prefs: SharedPreferences, routeId: String): EqualizerTarget? {
        val group = GoneSmartAudioProfiles.equalizerGroup
        val useRouteOverride = !AudioProfileRuntimePolicy.isStandardRoute(routeId) &&
            prefs.getBoolean(GoneSmartAudioProfiles.overrideKey(routeId, group.id), false)

        fun value(fieldId: String): String? {
            val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
            return if (useRouteOverride) {
                prefs.getString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), null)
                    ?.let(field::normalize)
            } else {
                standardValue(prefs, fieldId)
            }
        }

        val enabled = value("eq.enabled") ?: return null
        val storedPreset = value("eq.preset") ?: return null
        val preset = if (useRouteOverride &&
            storedPreset != GoneSmartAudioProfiles.CUSTOM_PRESET &&
            !AudioProfilePresetStorage.isCurrent(prefs)
        ) {
            GoneSmartAudioProfiles.CUSTOM_PRESET
        } else {
            storedPreset
        }

        // A route profile always owns a complete explicit shape. Selecting a
        // learned native preset copies that exact curve into these route values,
        // so the runtime never has to rediscover a preset while applying it.
        val shape = if (useRouteOverride) {
            readShape { fieldId ->
                prefs.getString(GoneSmartAudioProfiles.valueKey(routeId, fieldId), null)
            }
        } else {
            readShape { fieldId -> standardValue(prefs, fieldId) }
        }

        val source = when {
            !useRouteOverride -> "Standard"
            preset == GoneSmartAudioProfiles.CUSTOM_PRESET -> "Bluetooth Custom"
            shape != null -> "Bluetooth native preset $preset"
            else -> "Bluetooth Equalizer shape unavailable"
        }
        return EqualizerTarget(enabled, preset, shape, source)
    }

    private fun readShape(reader: (String) -> String?): Map<String, String>? {
        val shape = linkedMapOf<String, String>()
        GoneSmartAudioProfiles.equalizerShapeFieldIds.forEach { fieldId ->
            val field = GoneSmartAudioProfiles.field(fieldId) ?: return null
            val normalized = reader(fieldId)?.let(field::normalize) ?: return null
            shape[fieldId] = normalized
        }
        return shape
    }

    private fun writeNativePreferences(
        prefs: SharedPreferences,
        writes: List<NativeAudioMutation.PreferenceWrite>
    ): Boolean {
        if (writes.isEmpty()) return true
        val toWrite = writes.filterNot { write -> NativeAudioMutation.matches(prefs.all[write.key], write) }
        if (toWrite.isNotEmpty()) {
            val editor = prefs.edit()
            toWrite.forEach { write ->
                when (write.kind) {
                    NativeAudioMutation.Kind.BOOLEAN -> editor.putBoolean(write.key, write.value as Boolean)
                    NativeAudioMutation.Kind.INT -> editor.putInt(write.key, write.value as Int)
                    NativeAudioMutation.Kind.FLOAT -> editor.putFloat(write.key, write.value as Float)
                    NativeAudioMutation.Kind.STRING -> editor.putString(write.key, write.value as String)
                }
            }
            if (!runCatching { editor.commit() }.getOrDefault(false)) {
                Log.w(TAG, "Native GMMP preference commit failed")
                return false
            }
        }
        return writes.all { write -> NativeAudioMutation.matches(prefs.all[write.key], write) }
    }

    private fun applyEqualizerShape(routeId: String, target: EqualizerTarget): Boolean {
        val shape = target.shape ?: return false
        val runtimeTarget = GmmpEqualizerRuntimeBridge.current() ?: return false
        val enabled = when (target.enabled) {
            "true" -> true
            "false" -> false
            else -> return false
        }

        val fingerprint = buildString {
            append(routeId).append('|').append(target.preset).append('|').append(enabled).append('|')
            GoneSmartAudioProfiles.equalizerShapeFieldIds.forEach { fieldId ->
                append(fieldId).append('=').append(shape[fieldId]).append('|')
            }
        }
        if (lastAppliedEqFingerprint == fingerprint) return true

        val pre = shape["eq.pre_db"]?.toDoubleOrNull() ?: return false
        val bands = GoneSmartAudioProfiles.equalizerBandFieldIds.map { fieldId ->
            shape[fieldId]?.toDoubleOrNull() ?: return false
        }

        runtimeTarget.handler.post {
            val currentTarget = GmmpEqualizerRuntimeBridge.current()
            if (activeRouteId != routeId || currentTarget?.instance !== runtimeTarget.instance) return@post
            val applied = runCatching {
                runtimeTarget.preSetter.invoke(runtimeTarget.instance, pre)
                bands.forEachIndexed { index, value ->
                    runtimeTarget.bandSetter.invoke(runtimeTarget.instance, index, value)
                }
                runtimeTarget.enabledSetter.invoke(runtimeTarget.instance, enabled)
                true
            }.onFailure {
                Log.w(TAG, "Native Equalizer shape application failed", it)
            }.getOrDefault(false)

            if (applied) {
                lastAppliedEqFingerprint = fingerprint
                Log.i(
                    TAG,
                    "EQ SHAPE APPLIED | route=$routeId | source=${target.shapeSource}" +
                        " | preBands=${bands.size + 1} | enabled=$enabled"
                )
                if (AudioProfileRuntimePolicy.isStandardRoute(routeId) &&
                    runtimePreferences?.getBoolean(KEY_OVERLAY_ACTIVE, false) == true
                ) {
                    scheduleApply("Standard Equalizer restored")
                }
            }
        }
        return false
    }
}
