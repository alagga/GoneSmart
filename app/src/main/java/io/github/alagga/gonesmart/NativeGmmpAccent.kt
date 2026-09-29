package io.github.alagga.gonesmart

import android.os.Looper
import android.view.View
import java.lang.reflect.Proxy

/**
 * Shared read-only bridge to GMMP/Aesthetic's live !mainColorAccent stream.
 *
 * Folder Move chrome and reused native MaterialDialogs must use the same live
 * host palette instead of Android's static colorAccent fallback, which can
 * retain an unrelated/red theme value on the tested GMMP skin.
 */
internal object NativeGmmpAccent {
    /**
     * Synchronous read of GMMP Aesthetic's current accent. This uses the
     * same current-color getter already verified by the native multi-select
     * palette bridge, so first-frame dialog chrome never needs Android's
     * unrelated static/red colorAccent while waiting for !mainColorAccent.
     */
    fun current(view: View): Int? = runCatching {
        val loader = view.context.classLoader
            ?: view.javaClass.classLoader
            ?: error("GMMP classloader missing")
        val theme = runCatching {
            loader.loadClass("com.afollestad.aesthetic.a\$a")
                .getDeclaredMethod("c")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrElse {
            loader.loadClass("com.afollestad.aesthetic.Aesthetic")
                .getDeclaredMethod("get")
                .apply { isAccessible = true }
                .invoke(null)
        } ?: error("GMMP Aesthetic not initialized")
        val attr = view.resources.getIdentifier(
            "colorAccent", "attr", view.context.packageName
        )
        require(attr != 0) { "GMMP colorAccent attr unavailable" }
        val getter = theme.javaClass.declaredMethods.firstOrNull {
            it.name == "e" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }
            ?: error("GMMP current accent getter unavailable")
        (getter.invoke(theme, attr) as? Number)?.toInt()
    }.getOrNull()

    internal class Subscription(
        @Suppress("unused")
        private val observer: Any,
        private val disposables: MutableList<Any>
    ) {
        fun dispose() {
            disposables.toList().forEach { disposable ->
                runCatching {
                    disposable.javaClass.getMethod("b").invoke(disposable)
                }
            }
            disposables.clear()
        }
    }

    fun observe(
        view: View,
        onColor: (Int) -> Unit,
        onError: (Throwable?) -> Unit = {}
    ): Subscription? = runCatching {
        val loader = view.context.classLoader
            ?: view.javaClass.classLoader
            ?: error("GMMP classloader missing")
        val theme = runCatching {
            loader.loadClass("com.afollestad.aesthetic.a\$a")
                .getDeclaredMethod("c")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrElse {
            loader.loadClass("com.afollestad.aesthetic.Aesthetic")
                .getDeclaredMethod("get")
                .apply { isAccessible = true }
                .invoke(null)
        } ?: error("GMMP Aesthetic not initialized")
        val attr = view.resources.getIdentifier(
            "colorAccent", "attr", view.context.packageName
        )
        require(attr != 0) { "GMMP colorAccent attr unavailable" }
        val fallback = theme.javaClass.getDeclaredMethod(
            "b", Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
            .invoke(theme, attr)
            ?: error("GMMP accent observable missing")
        val utility = loader.loadClass("oy0")
        val method = utility.declaredMethods.first {
            it.name == "h" && it.parameterCount == 3 &&
                it.parameterTypes[0].isAssignableFrom(theme.javaClass) &&
                it.parameterTypes[1] == String::class.java
        }.apply { isAccessible = true }
        val observable = method.invoke(
            null, theme, "!mainColorAccent", fallback
        ) ?: error("GMMP !mainColorAccent observable unavailable")
        val observerType = loader.loadClass("nf3")
        val disposables = arrayListOf<Any>()
        val listener = Proxy.newProxyInstance(
            observerType.classLoader, arrayOf(observerType)
        ) { _, callback, args ->
            when (callback.name) {
                "a" -> (args?.firstOrNull() as? Number)?.let { value ->
                    val color = value.toInt()
                    // Aesthetic commonly emits the current value
                    // synchronously while we subscribe on GMMP's main
                    // thread. Posting that value delayed creation-dialog
                    // chrome by a visible frame (~80 ms on the test device).
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        onColor(color)
                    } else {
                        view.post { onColor(color) }
                    }
                }
                "c" -> args?.firstOrNull()?.let(disposables::add)
                "onError" -> onError(args?.firstOrNull() as? Throwable)
            }
            null
        }
        Subscription(listener, disposables).also {
            observable.javaClass.getMethod("b", observerType)
                .invoke(observable, listener)
        }
    }.onFailure(onError).getOrNull()
}
