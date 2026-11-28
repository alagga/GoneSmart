package io.github.alagga.gonesmart

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Independent instance of the installed GMMP Files-tab loading drawable.
 * The original progress view/drawable is never moved or mutated.
 */
internal class NativeQuickNavLoadingSource {
    private companion object {
        const val DRAWABLE =
            "me.zhanghai.android.materialprogressbar.IndeterminateHorizontalProgressDrawable"
    }
    private data class Sample(
        val height: Int,
        val tint: ColorStateList?,
        val mode: PorterDuff.Mode?,
        val padding: List<Int>,
        val intrinsicPadding: Boolean?
    )
    private var sample: Sample? = null
    private val observed = WeakHashMap<View, Boolean>()
    private var failedLoader: WeakReference<ClassLoader>? = null

    fun observe(quickNav: ViewGroup) {
        if (observed.put(quickNav, true) != null) return
        // Restrict to the Files QuickNav's nearest local containers. Do not
        // scan the Activity/decor or observe unrelated progress bars.
        var parent = quickNav.parent as? ViewGroup
        repeat(3) {
            val scope = parent ?: return
            val matches = ArrayList<ProgressBar>()
            var remaining = 48
            fun visit(view: View, depth: Int) {
                if (remaining-- <= 0 || depth > 3 || view === quickNav) return
                if (view is ProgressBar &&
                    view.indeterminateDrawable?.javaClass?.name == DRAWABLE
                ) matches.add(view)
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        visit(view.getChildAt(index), depth + 1)
                    }
                }
            }
            visit(scope, 0)
            if (matches.size > 1) return
            val bar = matches.singleOrNull()
            if (bar != null) {
                val drawable = bar.indeterminateDrawable ?: return
                val height = bar.height.takeIf { it > 0 }
                    ?: bar.layoutParams?.height?.takeIf { it > 0 }
                    ?: drawable.intrinsicHeight.takeIf { it > 0 }
                    ?: return
                val tint = runCatching {
                    bar.javaClass.getMethod("getSupportIndeterminateTintList")
                        .invoke(bar) as? ColorStateList
                }.getOrNull() ?: bar.indeterminateTintList
                val mode = runCatching {
                    bar.javaClass.getMethod("getSupportIndeterminateTintMode")
                        .invoke(bar) as? PorterDuff.Mode
                }.getOrNull() ?: bar.indeterminateTintMode
                sample = Sample(height, tint, mode,
                    listOf(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom),
                    runCatching {
                        drawable.javaClass.getMethod("getUseIntrinsicPadding")
                            .invoke(drawable) as? Boolean
                    }.getOrNull())
                rememberGeometry(quickNav, sample ?: return)
                if (BuildConfig.DEBUG) Log.i("GoneSmartPlaylist",
                    "FOLDER NATIVE QUICKNAV LOADING | source=live | height=" + height +
                        " | view=" + bar.javaClass.name +
                        " | padding=" + sample?.padding)
                return
            }
            parent = scope.parent as? ViewGroup
        }
    }

    @Suppress("DEPRECATION")
    private fun geometryKey(host: View): String? = runCatching {
        val version = host.context.packageManager.getPackageInfo(
            host.context.packageName, 0
        ).versionCode
        "v" + version + "_dpi" + host.resources.displayMetrics.densityDpi + "_"
    }.getOrNull()

    private fun geometryPrefs(host: View) = host.context.getSharedPreferences(
        "gonesmart_native_quicknav_loading", Context.MODE_PRIVATE
    )

    private fun rememberGeometry(host: View, source: Sample) {
        val key = geometryKey(host) ?: return
        val editor = geometryPrefs(host).edit().putInt(key + "height", source.height)
        source.padding.forEachIndexed { i, value -> editor.putInt(key + "padding" + i, value) }
        source.intrinsicPadding?.let { editor.putBoolean(key + "intrinsic", it) }
        editor.apply()
    }

    fun create(host: View): ProgressBar? {
        val loader = host.javaClass.classLoader ?: return null
        if (failedLoader?.get() === loader) return null
        val source = sample
        return runCatching {
            val type = loader.loadClass(DRAWABLE)
            require(Drawable::class.java.isAssignableFrom(type))
            // Construct a fresh native animation instance. A copied
            // ConstantState can retain the wrong animator/target state.
            val drawable = (type.getConstructor(Context::class.java)
                .newInstance(host.context) as Drawable).mutate()
            require(drawable is Animatable)
            val key = geometryKey(host)
            val prefs = geometryPrefs(host)
            val cachedHeight = key?.let { prefs.getInt(it + "height", 0) }
                ?.takeIf { it > 0 }
            val cachedIntrinsic = key?.let {
                if (prefs.contains(it + "intrinsic")) prefs.getBoolean(it + "intrinsic", false)
                else null
            }
            // Without a live/cached measurement use the installed library's
            // own unpadded intrinsic geometry, not a guessed pixel height.
            val useIntrinsicPadding = source?.intrinsicPadding ?: cachedIntrinsic ?: false
            runCatching {
                drawable.javaClass.getMethod("setUseIntrinsicPadding",
                    java.lang.Boolean.TYPE).invoke(drawable, useIntrinsicPadding)
            }
            val height = source?.height ?: cachedHeight ?: drawable.intrinsicHeight
            require(height > 0)
            // Reuse the framework wrapper which was device-confirmed in
            // 178e90f. The MaterialProgressBar constructor can reset the
            // supplied indeterminate drawable's tint/padding state.
            ProgressBar(host.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                isIndeterminate = true
                indeterminateDrawable = drawable
                val tint = source?.tint ?: NativeGmmpAccent.currentPrimary(host)
                    ?.let(ColorStateList::valueOf)
                val padding = source?.padding ?: if (cachedHeight != null && key != null) {
                    List(4) { prefs.getInt(key + "padding" + it, 0) }
                } else listOf(0, 0, 0, 0)
                setPadding(padding[0], padding[1], padding[2], padding[3])
                minimumHeight = 0
                if (tint != null) indeterminateTintList = tint
                source?.mode?.let { indeterminateTintMode = it }
                // Keep ProgressBar's actual drawable bounds matched to the
                // native thin bar even with a themed minimum/max height.
                maxHeight = height
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                visibility = View.GONE
                if (BuildConfig.DEBUG) Log.i("GoneSmartPlaylist",
                    "FOLDER NATIVE QUICKNAV LOADING | created=" + javaClass.name +
                        " | height=" + height + " | drawable=" + drawable.javaClass.simpleName +
                        " | source=" + (if (source != null) "live" else if (cachedHeight != null) "cached" else "intrinsic") +
                        " | intrinsicPadding=" + useIntrinsicPadding)
            }
        }.onFailure {
            failedLoader = WeakReference(loader)
            if (BuildConfig.DEBUG) Log.w("GoneSmartPlaylist",
                "FOLDER NATIVE QUICKNAV LOADING | original drawable unavailable; omitted", it)
        }.getOrNull()
    }
}
