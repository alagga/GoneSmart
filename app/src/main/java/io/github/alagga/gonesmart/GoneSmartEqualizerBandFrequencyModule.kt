package io.github.alagga.gonesmart

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.abs

/**
 * Learns GMMP's live Equalizer center frequencies without invoking unknown
 * obfuscated readers. Once the shared Equalizer target is proven, this module
 * hooks the two indexed double readers and only inspects calls GMMP makes by
 * itself. One reader must independently match the already proven band-gain
 * array for every active index; only the other reader may then graduate as the
 * frequency source if it yields one complete increasing audio-frequency vector.
 */
class GoneSmartEqualizerBandFrequencyModule : XposedModule() {

    companion object {
        private const val TAG = "GoneSmartAudioProfiles"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val COMPANION_PACKAGE = "io.github.alagga.gonesmart"
        private const val READER_EPSILON = 0.001
    }

    private data class Detection(
        val instance: Any,
        val bandCount: Int,
        val frequenciesHz: List<Double>
    )

    private val lock = Any()
    private val attemptedClasses = mutableSetOf<Class<*>>()
    private val readersByClass = mutableMapOf<Class<*>, List<Method>>()
    private val bandArrayByClass = mutableMapOf<Class<*>, Field>()
    private val readings = mutableMapOf<Method, MutableMap<Int, Double>>()
    private val gainMatches = mutableMapOf<Method, MutableSet<Int>>()

    private var activeInstance: Any? = null
    private var activeBandCount: Int? = null
    private var verifiedInstance: Any? = null
    private var verifiedBandCount: Int? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != GMMP_PACKAGE) return
        GmmpEqualizerRuntimeBridge.addReadyListener(::onEqualizerTargetReady)
    }

    private fun onEqualizerTargetReady() {
        val target = GmmpEqualizerRuntimeBridge.current() ?: return
        synchronized(lock) {
            if (activeInstance !== target.instance || activeBandCount != target.bandCount) {
                activeInstance = target.instance
                activeBandCount = target.bandCount
                verifiedInstance = null
                verifiedBandCount = null
                readings.clear()
                gainMatches.clear()
            }
        }
        installPassiveReaders(target.instance.javaClass)
    }

    private fun installPassiveReaders(owner: Class<*>) {
        val candidates: List<Method>
        val bandArray: Field
        synchronized(lock) {
            if (!attemptedClasses.add(owner)) return
            candidates = owner.declaredMethods
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .filter { method ->
                    method.returnType == Double::class.javaPrimitiveType &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
                .sortedBy { it.name }
            bandArray = owner.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .filter { it.type == DoubleArray::class.java }
                .singleOrNull()
                ?.apply { isAccessible = true }
                ?: run {
                    Log.w(TAG, "EQ BAND FREQUENCY OBSERVER unavailable | reason=band-array-ambiguous")
                    return
                }
            if (candidates.size != 2) {
                Log.w(
                    TAG,
                    "EQ BAND FREQUENCY OBSERVER unavailable | indexedDoubleReaders=${candidates.size} | expected=2"
                )
                return
            }
            readersByClass[owner] = candidates
            bandArrayByClass[owner] = bandArray
        }

        val installed = candidates.all { method -> installPassiveReader(method) }
        if (installed) {
            Log.i(TAG, "EQ BAND FREQUENCY OBSERVER ready | indexedDoubleReaders=2 | mode=passive")
        } else {
            synchronized(lock) {
                readersByClass.remove(owner)
                bandArrayByClass.remove(owner)
            }
            Log.w(TAG, "EQ BAND FREQUENCY OBSERVER unavailable | reason=hook-install-failed")
        }
    }

    private fun installPassiveReader(method: Method): Boolean = runCatching {
        method.isAccessible = true
        hook(method).intercept { chain ->
            val index = (runCatching { chain.getArg(0) }.getOrNull() as? Number)?.toInt()
            val instance = chain.thisObject
            val result = chain.proceed()
            val value = (result as? Number)?.toDouble()
            if (instance != null && index != null && value != null) {
                observeNaturalReaderCall(method, instance, index, value)
            }
            result
        }
        true
    }.onFailure {
        Log.w(TAG, "Could not install passive indexed Equalizer reader", it)
    }.getOrDefault(false)

    private fun observeNaturalReaderCall(method: Method, instance: Any, index: Int, value: Double) {
        if (!value.isFinite()) return
        val detection = synchronized(lock) {
            val target = GmmpEqualizerRuntimeBridge.current() ?: return@synchronized null
            if (target.instance !== instance || index !in 0 until target.bandCount) return@synchronized null

            if (activeInstance !== instance || activeBandCount != target.bandCount) {
                activeInstance = instance
                activeBandCount = target.bandCount
                verifiedInstance = null
                verifiedBandCount = null
                readings.clear()
                gainMatches.clear()
            }
            if (verifiedInstance === instance && verifiedBandCount == target.bandCount) return@synchronized null

            val owner = instance.javaClass
            val readers = readersByClass[owner] ?: return@synchronized null
            if (method !in readers) return@synchronized null
            val field = bandArrayByClass[owner] ?: return@synchronized null
            val bandArray = runCatching { field.get(instance) as? DoubleArray }.getOrNull()
                ?: return@synchronized null
            if (bandArray.size != target.bandCount) return@synchronized null

            readings.getOrPut(method) { mutableMapOf() }[index] = value
            if (bandArray[index].isFinite() && abs(bandArray[index] - value) <= READER_EPSILON) {
                gainMatches.getOrPut(method) { mutableSetOf() } += index
            }

            val expectedIndices = (0 until target.bandCount).toSet()
            val gainReaders = readers.filter { candidate ->
                gainMatches[candidate]?.containsAll(expectedIndices) == true
            }
            if (gainReaders.size != 1) return@synchronized null
            val gainReader = gainReaders.single()

            val frequencyCandidates = readers
                .filter { it != gainReader }
                .mapNotNull { candidate ->
                    val candidateReadings = readings[candidate] ?: return@mapNotNull null
                    val ordered = (0 until target.bandCount).map { bandIndex ->
                        candidateReadings[bandIndex] ?: return@mapNotNull null
                    }
                    GmmpEqualizerBandPolicy.validatedFrequencies(ordered, target.bandCount)
                }
            if (frequencyCandidates.size != 1) return@synchronized null

            Detection(instance, target.bandCount, frequencyCandidates.single())
        } ?: return

        if (publishDetection(detection)) {
            synchronized(lock) {
                val target = GmmpEqualizerRuntimeBridge.current()
                if (target?.instance === detection.instance && target.bandCount == detection.bandCount) {
                    verifiedInstance = detection.instance
                    verifiedBandCount = detection.bandCount
                }
            }
            Log.i(
                TAG,
                "EQ BAND FREQUENCIES | bands=${detection.bandCount}" +
                    " | hz=${detection.frequenciesHz.joinToString(",") { AudioProfileField.formatNumber(it) }}" +
                    " | source=passive-indexed-getters"
            )
        }
    }

    private fun publishDetection(detection: Detection): Boolean {
        val application = currentApplication() ?: return false
        val version = gmmpVersion(application) ?: return false
        val extras = Bundle().apply {
            putString(AudioProfileCompanionBridge.EXTRA_GMMP_VERSION, version)
            putInt(AudioProfileCompanionBridge.EXTRA_EQ_BAND_COUNT, detection.bandCount)
            putDoubleArray(
                AudioProfileCompanionBridge.EXTRA_EQ_BAND_FREQUENCIES,
                detection.frequenciesHz.toDoubleArray()
            )
        }
        if (AudioProfileCompanionBridge.report(
                application,
                AudioProfileCompanionBridge.METHOD_EQ_BAND_FREQUENCIES,
                extras
            )
        ) {
            return true
        }

        return runCatching {
            application.sendBroadcast(
                Intent(AudioProfileEventReceiver.ACTION_EQ_BAND_FREQUENCIES_OBSERVED)
                    .setPackage(COMPANION_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtras(extras)
            )
            true
        }.onFailure {
            Log.w(TAG, "Could not mirror Equalizer band frequencies to companion", it)
        }.getOrDefault(false)
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val method = activityThread.getDeclaredMethod("currentApplication").apply { isAccessible = true }
        method.invoke(null) as? Application
    }.getOrNull()

    private fun gmmpVersion(application: Application): String? = runCatching {
        @Suppress("DEPRECATION")
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
