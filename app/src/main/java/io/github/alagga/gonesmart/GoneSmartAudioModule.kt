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
        private const val PREF_PROBE_MAX_EVENTS = 400
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
     * Passive debug probe: records native SharedPreferences writes while the
     * maintainer changes EQ/effect controls in GMMP. It does not alter args or
     * results. Primitive values and a short non-framework call chain are
     * included so one combined device run can map both persistence keys and
     * the native writer boundary; String payloads remain redacted.
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
        if (key.isNullOrBlank()) return
        if (SystemClock.elapsedRealtime() - preferenceProbeStartedAt > PREF_PROBE_WINDOW_MS) return
        if (preferenceProbeEvents.get() >= PREF_PROBE_MAX_EVENTS) return

        val rendered = when (value) {
            null -> "<removed>"
            is Boolean, is Int, is Long, is Float, is Double -> value.toString()
            else -> "<${value.javaClass.simpleName}>"
        }
        // Include the value hash only in the in-memory deduplication key. This
        // lets repeated writes to the same String-backed DSP state appear as
        // separate probe events without exposing the String itself in Logcat.
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
