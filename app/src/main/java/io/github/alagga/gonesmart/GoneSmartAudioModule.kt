package io.github.alagga.gonesmart

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent feature family for output-specific GMMP audio profiles.
 *
 * Route discovery is intentionally public-API only and profile overrides are
 * kept in a separate RemotePreferences group. Until GMMP's native DSP writer
 * boundary has been passively proven, this module only observes routes and
 * preferences; it never guesses or mutates GMMP audio state.
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

        private val AUDIO_PREFERENCE_KEYS = setOf(
            "player_eqEnabled",
            "dspSettings_activePreset",
            "dspSettings_balanceEnabled",
            "dspSettings_balance",
            "player_forceMono",
            "dspSettings_limiterEnabled",
            "dspSettings_attack",
            "dspSettings_release",
            "dspSettings_bassBoostEnabled",
            "dspSettings_bassBoostStrength",
            "player_tempoEnabled",
            "player_tempo",
            "player_autoPitchCorrection",
            "dspSettings_virtualizerEnabled",
            "dspSettings_virtualizerStrength",
            "dspSettings_loudnessEnabled",
            "dspSettings_loudnessGain"
        )
    }

    private var audioPreferences: SharedPreferences? = null
    private var activeRouteId: String = GoneSmartAudioProfiles.LOCAL_ROUTE_ID
    private var routeObserver: AudioRouteObserver? = null

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
        resolveApplicationAndStart(attempt = 0)

        if (BuildConfig.DEBUG) {
            logNativeDspInventory(param.classLoader)
            installEqualizerCallProbe(param.classLoader)
            installPreferenceProbe()
        }
    }

    private fun resolveApplicationAndStart(attempt: Int) {
        val application = currentApplication()
        if (application != null) {
            if (routeObserver == null) {
                routeObserver = AudioRouteObserver(application) { route -> onRouteChanged(application, route) }
                routeObserver?.start()
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
        val prefs = audioPreferences ?: return
        val prefix = GoneSmartAudioProfiles.overrideKey(activeRouteId, "")
        val overrides = prefs.all.keys.filter { it.startsWith(prefix) }
        if (overrides.isEmpty()) {
            Log.i(TAG, "PROFILE INHERIT | route=$activeRouteId | reason=$reason | GMMP state unchanged")
        } else {
            Log.w(
                TAG,
                "PROFILE APPLY DEFERRED | route=$activeRouteId | overrides=${overrides.size}" +
                    " | native GMMP DSP mutation boundary is not proven yet"
            )
        }
    }

    /**
     * One debug-build inventory for the naturally observed 4.2.1 EQ-state
     * helper family. Obfuscated names are evidence only; this probe never
     * calls a candidate and is removed once a semantic writer is proven.
     */
    private fun logNativeDspInventory(classLoader: ClassLoader) {
        listOf("r61", "s61").forEach { candidateName ->
            val candidate = runCatching { classLoader.loadClass(candidateName) }.getOrNull() ?: return@forEach
            val constructors = candidate.declaredConstructors
                .map { constructor ->
                    candidate.simpleName + "(" + constructor.parameterTypes.joinToString(",") { it.name } + ")"
                }
                .ifEmpty { listOf("none") }
            Log.i(
                PROBE_TAG,
                "DSP CLASS | class=${candidate.name} | constructors=${constructors.joinToString(",")}"
            )

            candidate.declaredFields
                .sortedBy { it.name }
                .map { field ->
                    "${if (Modifier.isStatic(field.modifiers)) "static " else ""}${field.name}:${field.type.name}"
                }
                .chunked(12)
                .forEachIndexed { index, chunk ->
                    Log.i(
                        PROBE_TAG,
                        "DSP FIELDS | class=${candidate.name} | chunk=${index + 1} | ${chunk.joinToString(",")}"
                    )
                }

            candidate.declaredMethods
                .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
                .map { method ->
                    method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + "):" + method.returnType.name
                }
                .chunked(12)
                .forEachIndexed { index, chunk ->
                    Log.i(
                        PROBE_TAG,
                        "DSP METHODS | class=${candidate.name} | chunk=${index + 1} | ${chunk.joinToString(",")}"
                    )
                }
        }
    }

    /**
     * Passive EQ probe. The first device log proved r61 as the object saving
     * current EQ state, but not the semantic meaning of its compact methods.
     * Observe only the small set of mutation-shaped methods while the user
     * changes PRE/bands/presets; never invoke them for discovery.
     */
    private fun installEqualizerCallProbe(classLoader: ClassLoader) {
        val eqClass = runCatching { classLoader.loadClass("r61") }.getOrElse {
            Log.w(PROBE_TAG, "EQ call probe unavailable", it)
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
                        val args = method.parameterTypes.indices.joinToString(",") { index ->
                            runCatching { chain.getArg(index)?.toString() }.getOrNull() ?: "null"
                        }
                        Log.i(
                            PROBE_TAG,
                            "EQ CALL | method=${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})" +
                                " | args=$args | caller=${preferenceProbeCallSite()}"
                        )
                        chain.proceed()
                    }
                }.onFailure {
                    Log.w(PROBE_TAG, "Could not install EQ probe for ${method.name}", it)
                }
            }
    }

    /**
     * Passive debug probe for already-mapped audio preference identities only.
     * It no longer records unrelated app/WebView/analytics preferences.
     */
    private fun installPreferenceProbe() {
        val editorClass = runCatching {
            Class.forName("android.app.SharedPreferencesImpl\$EditorImpl")
        }.getOrElse {
            Log.w(PROBE_TAG, "SharedPreferences editor probe unavailable", it)
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
                        maybeLogPreferenceMutation(method.name, key, value)
                        chain.proceed()
                    }
                }.onFailure {
                    Log.w(PROBE_TAG, "Could not install preference probe for ${method.name}", it)
                }
            }
    }

    private fun maybeLogPreferenceMutation(methodName: String, key: String?, value: Any?) {
        if (key.isNullOrBlank() || key !in AUDIO_PREFERENCE_KEYS) return
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
                " | caller=${preferenceProbeCallSite()}"
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
