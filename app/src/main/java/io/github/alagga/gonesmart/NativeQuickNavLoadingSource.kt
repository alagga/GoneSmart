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
        val constantState: Drawable.ConstantState?,
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
                sample = Sample(height, bar.indeterminateTintList,
                    bar.indeterminateTintMode, drawable.constantState,
                    runCatching {
                        drawable.javaClass.getMethod("getUseIntrinsicPadding")
                            .invoke(drawable) as? Boolean
                    }.getOrNull())
                if (BuildConfig.DEBUG) Log.i("GoneSmartPlaylist",
                    "FOLDER NATIVE QUICKNAV LOADING | source=live | height=" + height)
                return
            }
            parent = scope.parent as? ViewGroup
        }
    }

    fun create(host: View): ProgressBar? {
        val loader = host.javaClass.classLoader ?: return null
        if (failedLoader?.get() === loader) return null
        val source = sample
        return runCatching {
            val type = loader.loadClass(DRAWABLE)
            require(Drawable::class.java.isAssignableFrom(type))
            val fromState = source?.constantState?.newDrawable(host.resources)
                ?.takeIf { type.isInstance(it) }
            val drawable = (fromState ?: type.getConstructor(Context::class.java)
                .newInstance(host.context) as Drawable).mutate()
            require(drawable is Animatable)
            source?.intrinsicPadding?.let { value ->
                runCatching {
                    drawable.javaClass.getMethod("setUseIntrinsicPadding",
                        java.lang.Boolean.TYPE).invoke(drawable, value)
                }
            }
            val height = source?.height ?: drawable.intrinsicHeight
            require(height > 0)
            ProgressBar(host.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
                isIndeterminate = true
                indeterminateDrawable = drawable
                val tint = source?.tint ?: NativeGmmpAccent.currentPrimary(host)
                    ?.let(ColorStateList::valueOf)
                if (tint != null) indeterminateTintList = tint
                source?.mode?.let { indeterminateTintMode = it }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                visibility = View.GONE
            }
        }.onFailure {
            failedLoader = WeakReference(loader)
            if (BuildConfig.DEBUG) Log.w("GoneSmartPlaylist",
                "FOLDER NATIVE QUICKNAV LOADING | original drawable unavailable; omitted", it)
        }.getOrNull()
    }
}
