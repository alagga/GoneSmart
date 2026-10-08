package io.github.alagga.gonesmart

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary, passive GMMP 4.2.1 Playlist Link acceptance probe.
 *
 * This entry exists only in debug builds in practice: release builds return
 * before installing anything. It never changes GMMP state. The probe makes
 * build identity and the next unresolved Playlist Link boundary observable
 * after the maintainer's normal in-app "Restart GMMP" workflow, where early
 * startup Logcat lines may not be part of the captured package-filtered log.
 *
 * Remove this entry again once Playlist Link has been device re-accepted.
 */
class PlaylistLinkRuntimeProbeModule : XposedModule() {
    companion object {
        private const val TAG = "GoneSmartPlaylistBridge"
        private const val GMMP_PACKAGE = "gonemad.gmmp"
    }

    private val bindingProbeLogged = AtomicBoolean(false)
    private val callbackShapeLogged = AtomicBoolean(false)

    override fun onPackageReady(param: PackageReadyParam) {
        if (
            !BuildConfig.DEBUG ||
            !param.isFirstPackage ||
            param.packageName != GMMP_PACKAGE
        ) {
            return
        }

        installEditorProbe(param)
        installChooserCallbackProbe(param)
    }

    private fun installEditorProbe(param: PackageReadyParam) {
        runCatching {
            val presenter = param.classLoader.loadClass("as4")
            val constructor = presenter.getDeclaredConstructor(
                Context::class.java,
                Bundle::class.java
            ).apply { isAccessible = true }

            hook(constructor).intercept { chain ->
                val result = chain.proceed()
                Log.i(
                    TAG,
                    "BRIDGE PROBE EDITOR | class=" +
                        (chain.getThisObject()?.javaClass?.name ?: "null") +
                        " | git=${BuildConfig.GIT_REVISION}"
                )

                if (bindingProbeLogged.compareAndSet(false, true)) {
                    val ready = runCatching {
                        PlaylistBridgeController().configure(param.classLoader)
                    }.onFailure {
                        Log.e(
                            TAG,
                            "BRIDGE PROBE BINDINGS FAILED" +
                                " | git=${BuildConfig.GIT_REVISION}",
                            it
                        )
                    }.getOrDefault(false)
                    Log.i(
                        TAG,
                        "BRIDGE PROBE BINDINGS | ready=$ready" +
                            " | git=${BuildConfig.GIT_REVISION}"
                    )
                }
                result
            }
        }.onFailure {
            Log.w(
                TAG,
                "BRIDGE PROBE EDITOR HOOK FAILED" +
                    " | git=${BuildConfig.GIT_REVISION}",
                it
            )
        }
    }

    private fun installChooserCallbackProbe(param: PackageReadyParam) {
        runCatching {
            val callbackClass = param.classLoader.loadClass("as4\$g")
            val accept = callbackClass.declaredMethods.single { method ->
                method.name == "accept" && method.parameterCount == 1
            }.apply { isAccessible = true }

            hook(accept).intercept { chain ->
                if (callbackShapeLogged.compareAndSet(false, true)) {
                    logCallbackShape(
                        callback = chain.getThisObject(),
                        payload = chain.getArg(0)
                    )
                }
                chain.proceed()
            }
        }.onFailure {
            Log.w(
                TAG,
                "BRIDGE PROBE CALLBACK HOOK FAILED" +
                    " | git=${BuildConfig.GIT_REVISION}",
                it
            )
        }
    }

    private fun logCallbackShape(callback: Any?, payload: Any?) {
        val fields = callback?.javaClass?.declaredFields
            ?.joinToString(",") { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(callback)
                }.getOrNull()
                val rendered = when {
                    field.type == java.lang.Boolean.TYPE ||
                        field.type == java.lang.Byte.TYPE ||
                        field.type == java.lang.Short.TYPE ||
                        field.type == java.lang.Integer.TYPE ||
                        field.type == java.lang.Long.TYPE ->
                        value?.toString() ?: "null"
                    value == null -> "null"
                    else -> value.javaClass.name
                }
                field.name + ":" + field.type.name + "=" + rendered
            }
            .orEmpty()

        Log.i(
            TAG,
            "BRIDGE PROBE CALLBACK | callback=" +
                (callback?.javaClass?.name ?: "null") +
                " | payload=" + (payload?.javaClass?.name ?: "null") +
                " | fields=$fields" +
                " | git=${BuildConfig.GIT_REVISION}"
        )
    }
}
