package io.github.alagga.gonesmart

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Decorates GMMP's existing Playlist / Smart-Playlist navigation labels
 * outside the classic navigation drawer.
 *
 * The drawer keeps its native MenuItem-based decoration in
 * PlaylistFolderPreviewController. This controller covers alternative GMMP
 * navigation layouts (top tabs and the Library chooser used by bottom
 * navigation) without assuming one fixed resource ID.
 */
internal class PlaylistNavigationBadgeController {
    companion object {
        private const val TAG = "GoneSmartPlaylist"
        private const val RESCAN_DEBOUNCE_MS = 120L
    }

    private val main = Handler(Looper.getMainLooper())
    private val playlistOriginals = WeakHashMap<TextView, CharSequence>()
    private val smartOriginals = WeakHashMap<TextView, CharSequence>()
    private val reportedSurfaces = linkedSetOf<String>()

    @Volatile private var playlistEnabled = false
    @Volatile private var smartEnabled = false

    private var activityRef: WeakReference<Activity>? = null
    private var decorRef: WeakReference<View>? = null
    private var refreshPending = false
    private var lastRefreshElapsed = Long.MIN_VALUE

    private val globalLayoutListener =
        ViewTreeObserver.OnGlobalLayoutListener {
            scheduleRefresh()
        }

    fun setOptions(
        playlistFoldersEnabled: Boolean,
        smartPlaylistFoldersEnabled: Boolean
    ) {
        val changed =
            playlistEnabled != playlistFoldersEnabled ||
                smartEnabled != smartPlaylistFoldersEnabled
        playlistEnabled = playlistFoldersEnabled
        smartEnabled = smartPlaylistFoldersEnabled
        if (changed) scheduleRefresh(force = true)
    }

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        val decor = activity.window?.decorView ?: return
        val previous = decorRef?.get()
        if (previous !== decor) {
            previous?.viewTreeObserver
                ?.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(globalLayoutListener)
            decorRef = WeakReference(decor)
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.addOnGlobalLayoutListener(
                    globalLayoutListener
                )
            }
        }
        scheduleRefresh(force = true)
    }

    private fun scheduleRefresh(force: Boolean = false) {
        val activity = activityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (refreshPending) return

        val now = SystemClock.elapsedRealtime()
        val delay = if (force || lastRefreshElapsed == Long.MIN_VALUE) {
            0L
        } else {
            (RESCAN_DEBOUNCE_MS - (now - lastRefreshElapsed)).coerceAtLeast(0L)
        }
        refreshPending = true
        main.postDelayed({
            refreshPending = false
            lastRefreshElapsed = SystemClock.elapsedRealtime()
            activityRef?.get()?.let(::refresh)
        }, delay)
    }

    private fun refresh(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val texts = arrayListOf<TextView>()
        collectTextViews(decor, texts)

        val playlistNames = listOfNotNull(
            NativeGmmpUiText.string(activity, "playlists"),
            NativeGmmpUiText.string(activity, "playlist")
        ).map(String::trim)
        val smartNames = listOfNotNull(
            NativeGmmpUiText.string(activity, "smart_playlists"),
            NativeGmmpUiText.smartPlaylist(activity)
        ).map(String::trim)

        val playlistCandidates = linkedSetOf<TextView>()
        val smartCandidates = linkedSetOf<TextView>()
        val tabPlaylist = linkedSetOf<TextView>()
        val tabSmart = linkedSetOf<TextView>()

        texts.forEach { text ->
            if (!isVisible(text) ||
                insideClassicDrawer(text) ||
                insideNativePlaylistContent(text) ||
                !hasClickableAncestor(text)
            ) return@forEach

            val original = originalText(text)
            val title = original.toString()
            val isPlaylist =
                PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
                    playlistNames,
                    title
                )
            val isSmart =
                PlaylistDrawerBadgePolicy.matchesExactLocalizedTitle(
                    smartNames,
                    title
                )
            when {
                isSmart -> {
                    smartCandidates += text
                    if (isTabLabel(text)) tabSmart += text
                }
                isPlaylist -> {
                    playlistCandidates += text
                    if (isTabLabel(text)) tabPlaylist += text
                }
            }
        }

        // For the Library chooser used by bottom navigation, require both
        // native destinations to be visible together. This avoids decorating
        // arbitrary user/media text that happens to equal one native noun.
        val hasNavigationPair =
            playlistCandidates.isNotEmpty() && smartCandidates.isNotEmpty()
        val playlistTargets = linkedSetOf<TextView>().apply {
            addAll(tabPlaylist)
            if (hasNavigationPair) addAll(playlistCandidates)
        }
        val smartTargets = linkedSetOf<TextView>().apply {
            addAll(tabSmart)
            if (hasNavigationPair) addAll(smartCandidates)
        }

        restoreMissing(playlistOriginals, playlistTargets)
        restoreMissing(smartOriginals, smartTargets)
        playlistTargets.forEach {
            updateSparkle(it, playlistEnabled, playlistOriginals)
        }
        smartTargets.forEach {
            updateSparkle(it, smartEnabled, smartOriginals)
        }

        if (BuildConfig.DEBUG) {
            if (tabPlaylist.isNotEmpty() || tabSmart.isNotEmpty()) {
                reportOnce("tabs", tabPlaylist.size, tabSmart.size)
            }
            val libraryPlaylist = playlistTargets.count { it !in tabPlaylist }
            val librarySmart = smartTargets.count { it !in tabSmart }
            if (libraryPlaylist > 0 || librarySmart > 0) {
                reportOnce("library", libraryPlaylist, librarySmart)
            }
        }
    }

    private fun reportOnce(
        surface: String,
        playlists: Int,
        smart: Int
    ) {
        val signature = "$surface:$playlists:$smart"
        if (reportedSurfaces.add(signature)) {
            Log.i(
                TAG,
                "FOLDER NAV BADGE | surface=$surface" +
                    " | playlists=$playlists | smart=$smart"
            )
        }
    }

    private fun originalText(view: TextView): CharSequence =
        playlistOriginals[view]
            ?: smartOriginals[view]
            ?: view.text
            ?: ""

    private fun restoreMissing(
        originals: WeakHashMap<TextView, CharSequence>,
        targets: Set<TextView>
    ) {
        originals.keys.toList()
            .filter { it !in targets || !it.isAttachedToWindow }
            .forEach { view ->
                originals.remove(view)?.let { original ->
                    if (view.isAttachedToWindow) view.text = original
                }
            }
    }

    private fun updateSparkle(
        view: TextView,
        enabled: Boolean,
        originals: WeakHashMap<TextView, CharSequence>
    ) {
        if (!enabled) {
            originals.remove(view)?.let { view.text = it }
            return
        }

        val current = view.text ?: return
        if (current is Spanned &&
            current.getSpans(
                0,
                current.length,
                BaselineCenteredSparkleSpan::class.java
            ).isNotEmpty()
        ) {
            return
        }

        // GMMP may rebind/relocalize the same native TextView. If it replaced
        // our span, treat the new native text as the next restoration source.
        originals.remove(view)
        val original = current
        val decorated = SpannableStringBuilder(original)
            .append("  \uFFFC")
        val marker = decorated.lastIndexOf('\uFFFC')
        decorated.setSpan(
            BaselineCenteredSparkleSpan(
                view.context,
                PlayerAutoDjBadgeController.SparkleBadgeDrawable(
                    0xFFA39AFF.toInt(),
                    scale = 1.85f
                )
            ),
            marker,
            marker + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        originals[view] = original
        view.text = decorated
    }

    private fun collectTextViews(
        view: View,
        result: MutableList<TextView>,
        depth: Int = 0
    ) {
        if (depth > 18) return
        if (view is TextView) result += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectTextViews(view.getChildAt(index), result, depth + 1)
            }
        }
    }

    private fun isVisible(view: View): Boolean =
        view.isAttachedToWindow &&
            view.isShown &&
            view.width > 0 &&
            view.height > 0

    private fun hasClickableAncestor(view: View): Boolean {
        var current: View? = view
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isClickable || candidate.isLongClickable) return true
            current = candidate.parent as? View
        }
        return false
    }

    private fun isTabLabel(view: View): Boolean {
        var current: View? = view
        repeat(7) {
            val candidate = current ?: return false
            val className = candidate.javaClass.name
            val idName = resourceName(candidate)
            if (className.contains("TabLayout", ignoreCase = true) ||
                className.contains("TabView", ignoreCase = true) ||
                idName.contains("tab", ignoreCase = true)
            ) return true
            current = candidate.parent as? View
        }
        return false
    }

    private fun insideClassicDrawer(view: View): Boolean {
        var current: View? = view
        repeat(9) {
            val candidate = current ?: return false
            val idName = resourceName(candidate)
            if (idName == "mainNavigationView" ||
                idName == "design_navigation_view"
            ) return true
            current = candidate.parent as? View
        }
        return false
    }

    private fun insideNativePlaylistContent(view: View): Boolean {
        var current: View? = view
        repeat(10) {
            val group = current as? ViewGroup
            if (group != null) {
                val adapter = runCatching {
                    group.javaClass.methods.firstOrNull {
                        it.name == "getAdapter" && it.parameterCount == 0
                    }?.invoke(group)
                }.getOrNull()
                val adapterName = adapter?.javaClass?.name
                if (adapterName == "zn3" || adapterName == "ls4") return true
            }
            current = current?.parent as? View
        }
        return false
    }

    private fun resourceName(view: View): String {
        val id = view.id
        if (!NativeResourceIdPolicy.canResolveEntryName(id)) return ""
        return runCatching {
            view.resources.getResourceEntryName(id)
        }.getOrDefault("")
    }
}
