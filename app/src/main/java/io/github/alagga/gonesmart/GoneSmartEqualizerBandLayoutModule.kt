package io.github.alagga.gonesmart

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Passive GMMP 4.2.1 Equalizer layout observer.
 *
 * Band count is a property of DspManager's native Equalizer creation boundary,
 * not of the r61 gain array we previously tried to use as a proxy. This module
 * observes that native boundary by semantic argument shape and feeds only a
 * validated 2..10 count into the shared Equalizer runtime bridge.
 */
class GoneSmartEqualizerBandLayoutModule : XposedModule() {

    companion object {
        private const val TAG = "GoneSmartAudioLayout"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
        private const val DSP_MANAGER = "gonemad.gmmp.audioengine.DspManager"
    }

    private val binding = AtomicReference<GmmpEqualizerDspLayoutResolver.Binding?>(null)

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != GMMP_PACKAGE) return

        val dspManager = runCatching {
            Class.forName(DSP_MANAGER, false, param.classLoader)
        }.onFailure {
            Log.w(TAG, "EQ DSP layout observer unavailable; live band count will fail closed", it)
        }.getOrNull() ?: return

        val candidates = GmmpEqualizerDspLayoutResolver.candidateMethods(dspManager)
        var installed = 0
        candidates.forEach { method ->
            method.isAccessible = true
            runCatching {
                hook(method).intercept { chain ->
                    val args = method.parameterTypes.indices.map { index ->
                        runCatching { chain.getArg(index) }.getOrNull()
                    }
                    val result = chain.proceed()
                    observeCall(method, args)
                    result
                }
                installed += 1
            }.onFailure {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EQ DSP candidate hook rejected | method=${GmmpEqualizerDspLayoutResolver.methodId(method)}")
                }
            }
        }

        Log.i(
            TAG,
            "EQ DSP LAYOUT OBSERVER | candidates=${candidates.size} | installed=$installed" +
                if (BuildConfig.DEBUG) {
                    " | methods=" + candidates.take(12).joinToString(";") {
                        GmmpEqualizerDspLayoutResolver.methodId(it)
                    }
                } else {
                    ""
                }
        )
    }

    private fun observeCall(method: Method, args: List<Any?>) {
        val existing = binding.get()
        val observation = if (existing != null) {
            GmmpEqualizerDspLayoutResolver.readBound(existing, method, args)
        } else {
            GmmpEqualizerDspLayoutResolver.infer(method, args)
        } ?: return

        if (existing == null) {
            if (!binding.compareAndSet(null, observation.binding)) {
                val winner = binding.get() ?: return
                val rebound = GmmpEqualizerDspLayoutResolver.readBound(winner, method, args) ?: return
                publish(rebound, learned = false)
                return
            }
            Log.i(
                TAG,
                "EQ DSP LAYOUT VERIFIED | method=${observation.binding.methodId}" +
                    " | bandArg=${observation.binding.bandArgIndex}" +
                    " | rateArg=${observation.binding.rateArgIndex}" +
                    " | channelArg=${observation.binding.channelArgIndex}"
            )
            publish(observation, learned = true)
            return
        }

        publish(observation, learned = false)
    }

    private fun publish(
        observation: GmmpEqualizerDspLayoutResolver.Observation,
        learned: Boolean
    ) {
        val changed = GmmpEqualizerRuntimeBridge.observeBandCount(observation.bandCount)
        if (changed || learned) {
            Log.i(
                TAG,
                "EQ BAND LAYOUT | source=dsp-manager | bands=${observation.bandCount}" +
                    " | rate=${observation.sampleRate} | channels=${observation.channels}" +
                    " | canonical=${GmmpEqualizerBandPolicy.MAX_BANDS}"
            )
        }
    }
}
