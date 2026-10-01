package io.github.alagga.gonesmart

import android.os.Looper
import android.view.View
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Shared read-only bridge to GMMP/Aesthetic's live !mainColorAccent stream.
 *
 * Folder Move chrome and reused native MaterialDialogs must use the same live
 * host palette instead of Android's static colorAccent fallback, which can
 * retain an unrelated/red theme value on the tested GMMP skin.
 */
internal object NativeGmmpAccent {
    @Volatile
    private var lastObservedLiveColor: Int? = null

    /**
     * Last value actually emitted by GMMP's !mainColorAccent stream.
     *
     * Unlike the Aesthetic attr getter below, this is safe for dynamic
     * playlist-selection/dialog chrome on the tested GMMP skin: the attr
     * getter can still expose the stale red static colorAccent.
     */
    fun lastObserved(): Int? = lastObservedLiveColor

    /**
     * Legacy synchronous read of Aesthetic's colorAccent attribute.
     * Do not use this for dynamic GMMP selection/dialog chrome: on the
     * tested skin it can return the stale red static accent while
     * !mainColorAccent already has a different live value.
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
        val resolverResults = utility.declaredMethods.mapNotNull { candidate ->
            val p = candidate.parameterTypes
            if (!Modifier.isStatic(candidate.modifiers) ||
                p.size != 3 ||
                p[1] != String::class.java ||
                candidate.returnType == java.lang.Void.TYPE ||
                !p[0].isAssignableFrom(theme.javaClass)
            ) return@mapNotNull null
            runCatching {
                candidate.isAccessible = true
                candidate to candidate.invoke(
                    null, theme, "!mainColorAccent", fallback
                )
            }.getOrNull()?.takeIf { it.second != null }
        }
        val resolver = when {
            resolverResults.size == 1 -> resolverResults.single()
            resolverResults.size > 1 ->
                resolverResults.singleOrNull { it.first.name == "h" }
                    ?: error(
                        "GMMP live accent resolver is runtime-ambiguous: " +
                            resolverResults.joinToString(",") {
                                it.first.name + "->" +
                                    (it.second?.javaClass?.name ?: "null")
                            }
                    )
            else -> null
        }
        // GMMP 4.2.1 no longer exposes the old oy0 resolver shape. The
        // Aesthetic colorAccent observable obtained directly from the live
        // theme is still dynamic and is preferable to a static theme color.
        val observable = resolver?.second ?: fallback

        // Never assume the old nf3 name. Resolve the observer interface from
        // the observable's actual subscribe boundary in this GMMP build.
        // Rx/Aesthetic variants may return either a disposable or void.
        val subscribeCandidates = observable.javaClass.methods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0].isInterface
        }
        val subscribe = subscribeCandidates.singleOrNull { it.name == "b" }
            ?: subscribeCandidates.singleOrNull()
            ?: error(
                "GMMP live accent subscribe boundary is not unique: " +
                    subscribeCandidates.joinToString(",") {
                        it.name + "(" + it.parameterTypes[0].name + ")"
                    }
            )
        val observerType = subscribe.parameterTypes.single()
        val disposables = arrayListOf<Any>()
        val listener = Proxy.newProxyInstance(
            observerType.classLoader, arrayOf(observerType)
        ) { _, callback, args ->
            val value = args?.firstOrNull()
            when {
                value is Number -> {
                    val color = value.toInt()
                    lastObservedLiveColor = color
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        onColor(color)
                    } else {
                        view.post { onColor(color) }
                    }
                }
                value is Throwable -> onError(value)
                value != null && callback.parameterCount == 1 -> {
                    // Rx disposable/onSubscribe callback. Keep only objects
                    // exposing a zero-arg dispose-like void method.
                    val disposable = value.javaClass.methods.any {
                        it.parameterCount == 0 &&
                            it.returnType == java.lang.Void.TYPE
                    }
                    if (disposable) disposables.add(value)
                }
            }
            null
        }
        Subscription(listener, disposables).also {
            subscribe.isAccessible = true
            subscribe.invoke(observable, listener)
        }
    }.onFailure(onError).getOrNull()
}
