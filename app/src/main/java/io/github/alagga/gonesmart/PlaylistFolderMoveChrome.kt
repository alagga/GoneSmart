package io.github.alagga.gonesmart

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * One implementation of the Playlist-folder Move chrome used by both
 * ordinary Playlist folders and Smart-Playlist folders.
 *
 * The controller still owns destination navigation and file/model changes;
 * this class owns only the native ActionMode, AestheticFab, live accent
 * observer and cleanup.
 */
internal class PlaylistFolderMoveChrome(
    private val multiSelect: PlaylistMultiSelectController,
    private val main: Handler,
    private val tag: String,
    private val surface: String
) {
    class State {
        var actionMode: android.view.ActionMode? = null
        var fab: View? = null
        var fabColor: Int? = null
        var lastColor: Int? = null
        var barTintApplied: Boolean = false
        var accentSubscription: NativeGmmpAccent.Subscription? = null
        var lastBottomOcclusion: Int = -1
    }

    fun install(
        state: State,
        list: View,
        label: String,
        isActive: () -> Boolean,
        addFab: (View, Int) -> Unit,
        positionFab: (View) -> Boolean,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        if (!isActive() || state.actionMode != null) return false
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode,
                menu: android.view.Menu
            ): Boolean {
                mode.title = label
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode,
                menu: android.view.Menu
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode,
                item: android.view.MenuItem
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (state.actionMode !== mode) return
                state.actionMode = null
                if (isActive()) {
                    Log.i(tag, "$surface MOVE UI | native back arrow cancelled")
                    onCancel()
                }
            }
        }
        val mode = runCatching {
            list.startActionMode(
                callback, android.view.ActionMode.TYPE_PRIMARY
            )
        }.onFailure {
            Log.w(tag, "$surface MOVE UI | native ActionMode unavailable", it)
        }.getOrNull() ?: return false
        state.actionMode = mode

        val fab = createNativeFab(list, label, onConfirm)
        if (fab == null) {
            state.actionMode = null
            mode.finish()
            return false
        }
        state.fab = fab
        val size = nativeFabSize(list)
        addFab(fab, size)
        fab.elevation = dp(list, 8).toFloat()
        fab.visibility = View.INVISIBLE

        observePalette(state, list, isActive)
        syncPalette(state, list)
        fab.addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->
            if (isActive()) positionFab(fab)
        }
        fab.post {
            if (state.fab === fab && isActive() && positionFab(fab)) {
                runCatching {
                    fab.javaClass.getMethod("show").invoke(fab)
                }.onFailure {
                    fab.visibility = View.VISIBLE
                }
            }
        }
        Log.i(
            tag,
            "$surface MOVE UI | shared native ActionMode/AestheticFab ready"
        )
        return true
    }

    fun syncPalette(state: State, list: View) {
        if (state.actionMode == null) return
        val fab = state.fab ?: return
        val color = state.fabColor
            ?: nativePaletteFallback(fab)
            ?: return
        val changed = state.lastColor != color
        val previousBarTint = state.barTintApplied
        state.lastColor = color
        if (changed) {
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
        }
        state.barTintApplied =
            multiSelect.tintNativeContextBar(list as ViewGroup, color)
        if (changed || (!previousBarTint && state.barTintApplied)) {
            Log.i(
                tag,
                "$surface MOVE UI | native picker FAB color=#" +
                    Integer.toHexString(color) +
                    " | originalActionBarTint=" + state.barTintApplied
            )
        }
    }

    fun close(state: State) {
        val mode = state.actionMode
        state.actionMode = null
        mode?.finish()
        state.fabColor = null
        state.lastColor = null
        state.barTintApplied = false
        state.lastBottomOcclusion = -1
        state.accentSubscription?.dispose()
        state.accentSubscription = null
        val fab = state.fab
        state.fab = null
        if (fab != null) {
            fab.visibility = View.GONE
            val host = fab.parent as? ViewGroup
            if (host != null) {
                main.post {
                    if (fab.parent === host) host.removeView(fab)
                }
            }
        }
    }

    fun nativeMiniPlayerTop(list: View): Int? {
        val visible = android.graphics.Rect()
        if (!list.getGlobalVisibleRect(visible)) return null
        return listOf(
            "miniPlayerWrapper",
            "miniPlayerLayout",
            "libraryTabMiniPlayer"
        ).mapNotNull { name ->
            val id = list.resources.getIdentifier(
                name, "id", list.context.packageName
            )
            if (id == 0) return@mapNotNull null
            val player = list.rootView.findViewById<View>(id)
                ?: return@mapNotNull null
            val bounds = android.graphics.Rect()
            if (player.isShown &&
                player.getGlobalVisibleRect(bounds) &&
                bounds.height() > 0 &&
                bounds.top > visible.top + visible.height() / 3
            ) {
                bounds.top
            } else null
        }.minOrNull()
    }

    private fun createNativeFab(
        list: View,
        label: String,
        onConfirm: () -> Unit
    ): View? = runCatching {
        val clazz = list.javaClass.classLoader
            ?.loadClass("com.afollestad.aesthetic.views.AestheticFab")
            ?: error("GMMP AestheticFab unavailable")
        val fab = clazz.getConstructor(
            android.content.Context::class.java,
            android.util.AttributeSet::class.java
        ).newInstance(list.context, null) as? View
            ?: error("GMMP AestheticFab is not a View")
        val image = fab as? ImageView
            ?: error("GMMP AestheticFab is not an ImageView")
        image.imageTintList = null
        image.setImageDrawable(PlaylistConfirmDrawable(dp(list, 24)))
        image.contentDescription = label
        image.setOnClickListener { onConfirm() }
        runCatching {
            clazz.getMethod(
                "setCustomSize", Int::class.javaPrimitiveType
            ).invoke(fab, nativeFabSize(list))
        }
        fab
    }.onFailure {
        Log.w(tag, "$surface MOVE UI | native AestheticFab clone failed", it)
    }.getOrNull()

    private fun nativeFabSize(list: View): Int =
        list.resources.getIdentifier(
            "design_fab_size_normal", "dimen", list.context.packageName
        ).takeIf { it != 0 }?.let {
            runCatching {
                list.resources.getDimensionPixelSize(it)
            }.getOrNull()
        } ?: dp(list, 56)

    private fun observePalette(
        state: State,
        list: View,
        isActive: () -> Boolean
    ) {
        if (state.accentSubscription != null || state.fab == null) return
        val subscription = NativeGmmpAccent.observe(
            list,
            onColor = { value ->
                if (isActive()) {
                    state.fabColor = value
                    syncPalette(state, list)
                }
            },
            onError = {
                Log.w(
                    tag,
                    "$surface MOVE UI | native FAB observer unavailable; " +
                        "using original AestheticFab live background",
                    it
                )
            }
        )
        state.accentSubscription = subscription
        if (subscription != null) {
            Log.i(
                tag,
                "$surface MOVE UI | native !mainColorAccent observer active"
            )
        }
    }

    private fun nativePaletteFallback(fab: View): Int? {
        val tint = runCatching {
            fab.javaClass.getMethod("getBackgroundTintList")
                .invoke(fab) as? android.content.res.ColorStateList
        }.getOrNull() ?: drawableTint(fab.background)
        return tint?.getColorForState(
            fab.drawableState, tint.defaultColor
        )?.takeIf { Color.alpha(it) >= 200 }
    }

    private fun drawableTint(
        drawable: Drawable?,
        depth: Int = 0
    ): android.content.res.ColorStateList? {
        if (drawable == null || depth > 6) return null
        val tint = runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "getTintList" && it.parameterCount == 0
            }?.invoke(drawable) as? android.content.res.ColorStateList
        }.getOrNull()
        if (tint != null) return tint
        val fill = runCatching {
            drawable.javaClass.methods.firstOrNull {
                it.name == "getFillColor" && it.parameterCount == 0
            }?.invoke(drawable) as? android.content.res.ColorStateList
        }.getOrNull()
        if (fill != null) return fill
        if (drawable is ColorDrawable) {
            return android.content.res.ColorStateList.valueOf(drawable.color)
        }
        if (drawable is android.graphics.drawable.LayerDrawable) {
            for (index in 0 until drawable.numberOfLayers) {
                drawableTint(
                    drawable.getDrawable(index), depth + 1
                )?.let { return it }
            }
        }
        if (drawable is android.graphics.drawable.InsetDrawable) {
            return drawableTint(drawable.drawable, depth + 1)
        }
        return null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()
}
