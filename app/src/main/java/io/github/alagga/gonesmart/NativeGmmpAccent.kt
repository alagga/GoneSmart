package io.github.alagga.gonesmart

import android.graphics.Color
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
    fun current(view: View): Int? =
        currentAttribute(view, "colorAccent")

    /**
     * GMMP's contextual bars/selection chrome follows its live primary
     * palette on the tested 4.2.1 skin. This is deliberately separate from
     * colorAccent, whose Aesthetic fallback is the stale red value seen in
     * the r13 device log.
     */
    fun currentPrimary(view: View): Int? =
        currentAttribute(view, "colorPrimary")

    private fun currentAttribute(view: View, name: String): Int? =
        runCatching {
            val (_, theme) = theme(view)
            val attr = view.resources.getIdentifier(
                name, "attr", view.context.packageName
            )
            require(attr != 0) { "GMMP $name attr unavailable" }
            val getter = theme.javaClass.declaredMethods.firstOrNull {
                it.name == "e" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.returnType == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: error("GMMP current palette getter unavailable")
            (getter.invoke(theme, attr) as? Number)
                ?.toInt()
                ?.takeIf(::isUsableColor)
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
    ): Subscription? = observeAttribute(
        view = view,
        attributeName = "colorAccent",
        dynamicName = "!mainColorAccent",
        rememberVerifiedDynamic = true,
        onColor = onColor,
        onError = onError
    )

    /**
     * Structural 4.2.1 fallback for selection/dialog chrome. Unlike
     * [observe], this never labels the ordinary colorAccent observable as
     * !mainColorAccent. It follows Aesthetic's live colorPrimary stream.
     */
    fun observePrimary(
        view: View,
        onColor: (Int) -> Unit,
        onError: (Throwable?) -> Unit = {}
    ): Subscription? = observeAttribute(
        view = view,
        attributeName = "colorPrimary",
        dynamicName = null,
        rememberVerifiedDynamic = false,
        onColor = onColor,
        onError = onError
    )

    private fun observeAttribute(
        view: View,
        attributeName: String,
        dynamicName: String?,
        rememberVerifiedDynamic: Boolean,
        onColor: (Int) -> Unit,
        onError: (Throwable?) -> Unit
    ): Subscription? = runCatching {
        val (loader, theme) = theme(view)
        val attr = view.resources.getIdentifier(
            attributeName, "attr", view.context.packageName
        )
        require(attr != 0) { "GMMP $attributeName attr unavailable" }
        val fallback = theme.javaClass.getDeclaredMethod(
            "b", Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
            .invoke(theme, attr)
            ?: error("GMMP $attributeName observable missing")

        val resolver = if (dynamicName == null) {
            null
        } else {
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
                        null, theme, dynamicName, fallback
                    )
                }.getOrNull()?.takeIf { it.second != null }
            }
            when {
                resolverResults.size == 1 -> resolverResults.single()
                resolverResults.size > 1 ->
                    resolverResults.singleOrNull { it.first.name == "h" }
                        ?: error(
                            "GMMP live palette resolver is runtime-ambiguous: " +
                                resolverResults.joinToString(",") {
                                    it.first.name + "->" +
                                        (it.second?.javaClass?.name ?: "null")
                                }
                        )
                else -> null
            }
        }

        val observable = resolver?.second ?: fallback
        val verifiedDynamic = dynamicName != null && resolver != null

        val subscribeCandidates = observable.javaClass.methods.filter {
            it.parameterCount == 1 &&
                it.parameterTypes[0].isInterface
        }
        val subscribe = subscribeCandidates.singleOrNull { it.name == "b" }
            ?: subscribeCandidates.singleOrNull()
            ?: error(
                "GMMP live palette subscribe boundary is not unique: " +
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
                    if (!isUsableColor(color)) {
                        return@newProxyInstance null
                    }
                    if (rememberVerifiedDynamic && verifiedDynamic) {
                        lastObservedLiveColor = color
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        onColor(color)
                    } else {
                        view.post { onColor(color) }
                    }
                }
                value is Throwable -> onError(value)
                value != null && callback.parameterCount == 1 -> {
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

    private fun theme(view: View): Pair<ClassLoader, Any> {
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
        return loader to theme
    }

    private fun isUsableColor(color: Int): Boolean =
        color != Color.TRANSPARENT && Color.alpha(color) >= 200

}