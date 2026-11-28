package io.github.alagga.gonesmart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DefaultItemAnimator

/**
 * Shared native-looking folder chrome for both Playlist and Smart-Playlist
 * browsers. Data/model ownership intentionally stays in the two controllers.
 */
internal object PlaylistFolderUiKit {

    data class RowStyle(
        val rowLayoutId: Int,
        val titleViewId: Int,
        val rowHeight: Int,
        val textColor: Int,
        val textSizePx: Float,
        val typeface: Typeface?,
        val titleGravity: Int,
        val titlePaddingStart: Int,
        val titlePaddingEnd: Int,
        val effectivePaint: TextPaint,
        val letterSpacing: Float,
        val textScaleX: Float,
        val includeFontPadding: Boolean,
        val lineSpacingExtra: Float,
        val lineSpacingMultiplier: Float,
        val maxLines: Int,
        val ellipsize: android.text.TextUtils.TruncateAt?,
        val rowBackground: Drawable.ConstantState?,
        val titleInset: Int = 0,
        val accentColor: Int = Color.TRANSPARENT
    )

    data class BreadcrumbSegment(
        val key: String,
        val label: String
    )

    /**
     * The installed Files fragment uses a one-span horizontal GridLayoutManager.
     * Its SafeGridLayoutManager merely catches layout exceptions; do not
     * silently swallow an inconsistent module adapter transaction.
     */
    fun newQuickNav(host: View): RecyclerView = RecyclerView(host.context).apply {
        layoutManager = GridLayoutManager(context, 1, RecyclerView.HORIZONTAL, false)
        itemAnimator = DefaultItemAnimator()
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        NativeQuickNavLoadingSource.applyHeaderXml(this)
    }

    /** Files XML places progress at BOTTOM in the same wrap-content frame. */
    fun wrapQuickNav(header: RecyclerView): FrameLayout = FrameLayout(header.context).apply {
        elevation = header.elevation
        header.elevation = 0f
        addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    data class BreadcrumbAlignment(
        val measuredTextStart: Int,
        val appliedPaddingStart: Int?,
        val aligned: Boolean
    )

    private var rememberedNativeAddTitle: CharSequence? = null

    fun selectionTitle(context: android.content.Context, count: Int): String {
        val id = context.resources.getIdentifier(
            "num_selected", "string", context.packageName
        )
        if (id != 0) {
            runCatching { context.getString(id, count) }
                .getOrNull()
                ?.takeUnless(String::isBlank)
                ?.let { return it }
        }
        return count.toString()
    }

    fun rememberNativeAddTitle(
        menu: android.view.Menu,
        context: android.content.Context
    ) {
        val add = findNativeAddItem(menu, context) ?: return
        add.title?.let { rememberedNativeAddTitle = it }
    }

    fun installNativeFolderAddMenu(
        menu: android.view.Menu,
        context: android.content.Context,
        itemId: Int,
        rowTextColor: Int?,
        preferRememberedAddTitle: Boolean,
        onClick: () -> Unit
    ): android.view.MenuItem? {
        menu.findItem(itemId)?.let { return it }
        val nativeAdd = findNativeAddItem(menu, context)
        if (!preferRememberedAddTitle) {
            nativeAdd?.title?.let { rememberedNativeAddTitle = it }
        }
        val title = if (preferRememberedAddTitle) {
            rememberedNativeAddTitle
                ?: NativeGmmpUiText.string(context, "add")
                ?: NativeGmmpUiText.string(context, "add_to_playlist")
                ?: nativeAdd?.title
        } else {
            nativeAdd?.title
        } ?: return null

        val iconId = context.resources.getIdentifier(
            "ic_gm_new_folder", "drawable", context.packageName
        )
        if (iconId == 0) return null
        val label = SpannableStringBuilder(title)
        context.getDrawable(iconId)?.mutate()?.let { icon ->
            val fallbackSize =
                (18f * context.resources.displayMetrics.density).toInt()
            val width = icon.intrinsicWidth.takeIf { it > 0 } ?: fallbackSize
            val height = icon.intrinsicHeight.takeIf { it > 0 } ?: fallbackSize
            val spanColor = (title as? Spanned)?.let { source ->
                source.getSpans(
                    0, source.length, ForegroundColorSpan::class.java
                ).lastOrNull()?.foregroundColor
            }
            icon.setTint(
                spanColor ?: rowTextColor ?: resolveTextColor(context)
            )
            icon.setBounds(0, 0, width, height)
            label.insert(0, "\uFFFC  ")
            label.setSpan(
                ImageSpan(icon, ImageSpan.ALIGN_BOTTOM),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val order = (nativeAdd?.order ?: 0) + 1
        return menu.add(
            android.view.Menu.NONE, itemId, order, label
        ).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                onClick()
                true
            }
        }
    }

    /**
     * A root-level folder header is GONE, not a visible quickNav with an
     * animated empty dataset. Retire its old holders and pending removal
     * animations while hidden. Reopening starts a fresh native layout;
     * nested visible navigation keeps the ordinary native item animation.
     */
    fun hideAndResetBreadcrumb(
        header: RecyclerView,
        adapter: NativeFolderBreadcrumbAdapter,
        styleSignature: String
    ) {
        adapter.rootClosing = false
        adapter.rootOpening = false
        header.visibility = View.GONE
        if (header.adapter !== adapter ||
            !NativeQuickNavDiff.needsHiddenReset(adapter.itemCount)
        ) return
        header.stopScroll()
        header.itemAnimator?.endAnimations()
        // Detach only our own adapter. RecyclerView removes/recycles its
        // existing children and clears pending adapter updates natively.
        header.adapter = null
        adapter.submit(emptyList(), styleSignature)
        header.adapter = adapter
        header.scrollToPosition(0)
    }

    /**
     * Use RecyclerView's own smooth-scrolling path after the new holder
     * layout, without forcing an early scrollToPosition jump. Generation
     * and detach terminate the observer; no posted retry loop.
     */
    /**
     * Files keeps Storage visible at its root. Our root hides the whole
     * strip, but only after the same native suffix-removal transaction
     * finishes. A new navigation cancels the retiring strip before it
     * can expose a stale holder from the previous folder.
     */
    fun returnBreadcrumbToRoot(
        header: RecyclerView,
        adapter: NativeFolderBreadcrumbAdapter,
        styleSignature: String,
        onHidden: () -> Unit = {}
    ) {
        if (adapter.rootClosing) return
        val root = adapter.rootSegment()
        if (header.visibility != View.VISIBLE || root == null ||
            !header.isAttachedToWindow || !header.isShown || adapter.itemCount <= 1
        ) {
            hideAndResetBreadcrumb(header, adapter, styleSignature)
            onHidden()
            return
        }
        adapter.rootOpening = false
        adapter.rootClosing = true
        header.stopScroll()
        adapter.submit(listOf(root), styleSignature)
        header.scrollToPosition(0)
        val revision = adapter.pathRevision
        val tree = header.viewTreeObserver
        fun valid() = adapter.rootClosing && adapter.pathRevision == revision &&
            header.adapter === adapter && header.isAttachedToWindow
        fun complete() {
            if (!valid()) return
            hideAndResetBreadcrumb(header, adapter, styleSignature)
            onHidden()
        }
        if (!tree.isAlive) {
            complete()
            return
        }
        var attempts = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
        fun stop() {
            if (tree.isAlive) tree.removeOnPreDrawListener(listener)
            val live = header.viewTreeObserver
            if (live !== tree && live.isAlive) live.removeOnPreDrawListener(listener)
            header.removeOnAttachStateChangeListener(detach)
        }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = stop()
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (!valid()) {
                stop()
            } else if ((!header.hasPendingAdapterUpdates() &&
                    !header.isLayoutRequested && !header.isComputingLayout) ||
                    ++attempts >= 12
            ) {
                stop()
                // RecyclerView schedules its animator runner on the next
                // frame. Observe the actual animator after layout; never
                // call animateRemove or invent a timeout/duration.
                header.postOnAnimation {
                    if (valid()) {
                        val animator = header.itemAnimator
                        if (animator == null) complete()
                        else animator.isRunning { complete() }
                    }
                }
            }
            true
        }
        header.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }

    fun submitBreadcrumb(
        header: RecyclerView,
        adapter: NativeFolderBreadcrumbAdapter,
        next: List<BreadcrumbSegment>,
        styleSignature: String,
        animateRootOpening: Boolean,
        isCurrent: () -> Boolean,
        onCommitted: (Boolean) -> Unit
    ) {
        // A second navigation may arrive while the previous root return
        // still owns disappearing holders. Retire them synchronously while
        // hidden, and only then open the new path.
        if (adapter.rootClosing) {
            hideAndResetBreadcrumb(header, adapter, styleSignature)
            header.visibility = View.VISIBLE
        }
        adapter.rootOpening = false
        val plan = adapter.plan(next)
        // Native Files always starts with the Storage holder. Establish
        // that same real layout before the first child append, without
        // replaying ItemAnimator callbacks or retaining an old folder.
        if (!animateRootOpening || !plan.isForward || plan.retainedItems != 0 ||
            next.size <= 1 || !header.isAttachedToWindow
        ) {
            val changed = adapter.submit(next, styleSignature)
            if (changed) scrollBreadcrumbToNativeEnd(header)
            onCommitted(changed)
            return
        }
        adapter.rootOpening = true
        adapter.submit(next.take(1), styleSignature)
        val revision = adapter.pathRevision
        val tree = header.viewTreeObserver
        if (!tree.isAlive) {
            adapter.rootOpening = false
            val changed = adapter.submit(next, styleSignature)
            if (changed) scrollBreadcrumbToNativeEnd(header)
            onCommitted(changed)
            return
        }
        var attempts = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
        fun stop() {
            if (tree.isAlive) tree.removeOnPreDrawListener(listener)
            val live = header.viewTreeObserver
            if (live !== tree && live.isAlive) live.removeOnPreDrawListener(listener)
            header.removeOnAttachStateChangeListener(detach)
        }
        fun valid() = isCurrent() && adapter.rootOpening &&
            header.adapter === adapter && adapter.pathRevision == revision &&
            header.visibility == View.VISIBLE
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = stop()
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (!valid()) {
                stop()
            } else if ((!header.isLayoutRequested && !header.isComputingLayout &&
                    header.findViewHolderForAdapterPosition(0) != null) ||
                    ++attempts >= 12
            ) {
                stop()
                // The Storage-only native layout draws first. The following
                // ordinary adapter append is eligible for RecyclerView's
                // normal add animation, including the installed durations.
                header.postOnAnimation {
                    if (valid() && header.isAttachedToWindow) {
                        adapter.rootOpening = false
                        val changed = adapter.submit(next, styleSignature)
                        if (changed) scrollBreadcrumbToNativeEnd(header)
                        onCommitted(changed)
                    }
                }
            }
            true
        }
        header.addOnAttachStateChangeListener(detach)
        tree.addOnPreDrawListener(listener)
    }

    /**
     * Verified GMMP 4.2.1 mg1.t3 publishes the new list, dispatches DiffUtil
     * updates, then calls scrollToPosition(last) in the SAME UI transaction.
     * It does not post a smooth scroll or force manual add callbacks.
     */
    private fun scrollBreadcrumbToNativeEnd(header: RecyclerView) {
        val last = (header.adapter?.itemCount ?: 0) - 1
        if (last >= 0) header.scrollToPosition(last)
    }

    fun alignBreadcrumbStart(
        header: RecyclerView,
        expectedTextStart: Int,
        maxCorrectionPx: Int,
        allowCorrection: Boolean = true
    ): BreadcrumbAlignment? {
        if (!header.isAttachedToWindow || header.width <= 0 ||
            header.canScrollHorizontally(-1)
        ) return null
        val first = header.layoutManager?.findViewByPosition(0) ?: return null
        val label = findFirstTextView(first) ?: return null
        if (first.width <= 0 || label.width <= 0 || header.isLayoutRequested ||
            first.translationX != 0f || label.translationX != 0f ||
            header.itemAnimator?.isRunning == true
        ) {
            return null
        }
        val headerLocation = IntArray(2)
        val labelLocation = IntArray(2)
        header.getLocationOnScreen(headerLocation)
        label.getLocationOnScreen(labelLocation)
        val measured = labelLocation[0] - headerLocation[0] +
            label.compoundPaddingStart
        if (measured < 0 || expectedTextStart < 0) return null
        val correction = if (allowCorrection) {
            NativeQuickNavInsetPolicy.correctedPadding(
                currentPadding = header.paddingStart,
                measuredTextStart = measured,
                expectedTextStart = expectedTextStart,
                maxCorrection = maxCorrectionPx
            )
        } else null
        if (correction != null) {
            header.setPaddingRelative(
                correction,
                header.paddingTop,
                header.paddingEnd,
                header.paddingBottom
            )
        }
        return BreadcrumbAlignment(
            measuredTextStart = measured,
            appliedPaddingStart = correction,
            aligned = correction == null
        )
    }

    /**
     * Calibrate the real laid-out native metadata holder before its first
     * corrected frame is drawn, not in a later animation callback. Only an
     * actual padding change cancels one draw; RecyclerView/GMMP still owns
     * layout, item animation and scrolling. No timer or forced redraw loop.
     */
    fun alignBreadcrumbBeforeDraw(
        header: RecyclerView,
        expectedTextStart: Int,
        maxCorrectionPx: Int,
        isCurrent: () -> Boolean,
        onAlignment: (BreadcrumbAlignment) -> Unit = {}
    ) {
        if (!header.isAttachedToWindow) return
        val originalTree = header.viewTreeObserver
        if (!originalTree.isAlive) return
        val alignment = NativeQuickNavInsetPolicy.DrawAlignment()
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var detachListener: View.OnAttachStateChangeListener
        fun stop() {
            if (originalTree.isAlive) originalTree.removeOnPreDrawListener(listener)
            val liveTree = header.viewTreeObserver
            if (liveTree !== originalTree && liveTree.isAlive) {
                liveTree.removeOnPreDrawListener(listener)
            }
            header.removeOnAttachStateChangeListener(detachListener)
        }
        detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = stop()
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (!isCurrent() || !header.isAttachedToWindow ||
                header.visibility != View.VISIBLE ||
                header.canScrollHorizontally(-1)
            ) {
                stop()
                return@OnPreDrawListener true
            }
            val result = alignBreadcrumbStart(
                header,
                expectedTextStart,
                maxCorrectionPx,
                allowCorrection = alignment.mayCorrect
            )
            val step = alignment.onMeasurement(
                measurementAvailable = result != null,
                paddingApplied = result?.appliedPaddingStart != null
            )
            if (!step.keepPending) stop()
            result?.let(onAlignment)
            // setPaddingRelative requested native layout. Do not paint the
            // now obsolete holder geometry for a single transitional frame.
            step.draw
        }
        header.addOnAttachStateChangeListener(detachListener)
        originalTree.addOnPreDrawListener(listener)
    }

    fun menuButtonId(host: View): Int =
        host.resources.getIdentifier(
            "rvContextMenu", "id", host.context.packageName
        )

    fun contextAnchor(row: View, host: View): View? {
        val id = menuButtonId(host)
        return id.takeIf { it != 0 }?.let(row::findViewById)
    }

    fun firstBoundContextMenu(list: ViewGroup): ImageView? {
        val id = menuButtonId(list)
        if (id == 0) return null
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index) ?: continue
            val button = row.findViewById<ImageView>(id) ?: continue
            if (button.visibility == View.VISIBLE &&
                button.hasOnClickListeners()
            ) return button
        }
        return null
    }

    fun createRow(
        parent: ViewGroup?,
        host: ViewGroup,
        text: String,
        style: RowStyle?,
        folder: Boolean,
        selected: Boolean,
        selectionAccent: Int,
        contextMenuSource: ImageView?,
        onContext: ((View) -> Unit)?
    ): View {
        val layoutId = style?.rowLayoutId?.takeIf { it != 0 } ?: 0
        val template = if (layoutId != 0) {
            runCatching {
                LayoutInflater.from(host.context).inflate(
                    layoutId, parent, false
                )
            }.getOrNull()
        } else null
        val title = if (template != null && style?.titleViewId != 0) {
            template.findViewById<TextView>(style!!.titleViewId)
        } else null

        if (template != null && title != null && style != null) {
            applyTitleStyle(title, text, style)
            template.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, style.rowHeight
            )
            template.minimumHeight = style.rowHeight
            hideOtherText(template, title)
            val contextConfigured = configureContextMenu(
                template, host, contextMenuSource, onContext
            )
            if (folder) addFolderIcon(template, title, host, style)
            template.background =
                style.rowBackground?.newDrawable(host.resources)?.mutate()
                    ?: selectableBackground(host)
            applySelection(template, selected, selectionAccent)
            template.isClickable = true
            template.isFocusable = true
            return if (onContext != null && !contextConfigured) {
                wrapContextMenuFallback(
                    template, host, style.rowHeight,
                    contextMenuSource, onContext
                )
            } else template
        }

        val fallbackTextColor = style?.textColor ?: resolveTextColor(host)
        val height = style?.rowHeight ?: dp(host, 54)
        val fallback = TextView(host.context).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            minHeight = height
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height
            )
            setTextColor(fallbackTextColor)
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                style?.textSizePx
                    ?: resources.displayMetrics.scaledDensity * 16f
            )
            typeface = style?.typeface
            background = style?.rowBackground
                ?.newDrawable(resources)?.mutate()
                ?: selectableBackground(host)
            val start = style?.titleInset?.takeIf { it > 0 } ?: dp(host, 12)
            setPadding(start, 0, dp(host, 12), 0)
            if (folder) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    FolderOutlineDrawable(fallbackTextColor, dp(host, 24)),
                    null, null, null
                )
                compoundDrawablePadding = dp(host, 12)
            }
        }
        applySelection(fallback, selected, selectionAccent)
        return if (onContext != null) {
            wrapContextMenuFallback(
                fallback, host, height, contextMenuSource, onContext
            )
        } else fallback
    }

    fun showDeleteOnlyPopup(
        anchor: View,
        menuResourceName: String,
        deleteItemName: String = "menuContextDelete",
        onDelete: () -> Unit
    ): Boolean {
        val context = anchor.context
        val menuId = context.resources.getIdentifier(
            menuResourceName, "menu", context.packageName
        )
        val deleteId = context.resources.getIdentifier(
            deleteItemName, "id", context.packageName
        )
        if (menuId == 0 || deleteId == 0) return false
        val popup = android.widget.PopupMenu(context, anchor)
        if (!runCatching {
                popup.menuInflater.inflate(menuId, popup.menu)
            }.isSuccess
        ) return false
        val originalDelete = popup.menu.findItem(deleteId) ?: return false
        val title = originalDelete.title
        val icon = originalDelete.icon
        popup.menu.clear()
        popup.menu.add(
            android.view.Menu.NONE, deleteId, 0, title
        ).setIcon(icon)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == deleteId) {
                onDelete()
                true
            } else false
        }
        popup.show()
        return true
    }

    private fun applyTitleStyle(
        title: TextView,
        text: String,
        style: RowStyle
    ) {
        title.text = text
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSizePx)
        title.setTextColor(style.textColor)
        if (style.typeface != null) title.typeface = style.typeface
        title.gravity = style.titleGravity
        title.letterSpacing = style.letterSpacing
        title.textScaleX = style.textScaleX
        title.includeFontPadding = style.includeFontPadding
        title.setLineSpacing(
            style.lineSpacingExtra, style.lineSpacingMultiplier
        )
        title.maxLines = style.maxLines
        title.ellipsize = style.ellipsize
        title.setPaddingRelative(
            style.titlePaddingStart,
            title.paddingTop,
            style.titlePaddingEnd,
            title.paddingBottom
        )
        title.paint.set(style.effectivePaint)
        title.requestLayout()
    }

    private fun hideOtherText(root: View, title: TextView) {
        fun walk(view: View) {
            if (view is TextView && view !== title) {
                view.visibility = View.GONE
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(root)
    }

    private fun applySelection(
        root: View,
        selected: Boolean,
        accent: Int
    ) {
        if (selected) {
            root.foreground = ColorDrawable(
                withAlpha(
                    accent.takeIf { it != Color.TRANSPARENT }
                        ?: resolveTextColor(root),
                    0x80
                )
            )
        }
    }

    private fun addFolderIcon(
        root: View,
        title: TextView,
        host: View,
        style: RowStyle
    ) {
        val content = root as? ViewGroup ?: return
        val image = ImageView(host.context).apply {
            setImageDrawable(
                FolderOutlineDrawable(style.textColor, dp(host, 24))
            )
            contentDescription = NativeGmmpUiText.string(host.context, "folder")
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = dp(host, 24)
        if (content is FrameLayout) {
            content.addView(
                image,
                FrameLayout.LayoutParams(
                    size, size, Gravity.START or Gravity.CENTER_VERTICAL
                ).apply { marginStart = dp(host, 12) }
            )
        } else {
            content.addView(image, ViewGroup.LayoutParams(size, size))
        }
        title.setPaddingRelative(
            title.paddingStart + dp(host, 28),
            title.paddingTop,
            title.paddingEnd,
            title.paddingBottom
        )
    }

    private fun configureContextMenu(
        root: View,
        host: View,
        source: ImageView?,
        onContext: ((View) -> Unit)?
    ): Boolean {
        val id = menuButtonId(host)
        if (id == 0) return false
        val button = root.findViewById<ImageView>(id) ?: return false
        if (onContext == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return true
        }
        styleContextMenuButton(button, host, source, onContext)
        return true
    }

    private fun wrapContextMenuFallback(
        content: View,
        host: View,
        rowHeight: Int,
        source: ImageView?,
        onContext: (View) -> Unit
    ): View {
        val wrapper = FrameLayout(host.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
            )
            minimumHeight = rowHeight
        }
        wrapper.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
            )
        )
        val width = source?.width?.takeIf { it > 0 } ?: dp(host, 48)
        val button = ImageButton(host.context).apply {
            val nativeId = menuButtonId(host)
            if (nativeId != 0) id = nativeId
            background = selectableBackground(host, borderless = true)
        }
        styleContextMenuButton(button, host, source, onContext)
        wrapper.addView(
            button,
            FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        return wrapper
    }

    private fun styleContextMenuButton(
        button: ImageView,
        host: View,
        source: ImageView?,
        onContext: (View) -> Unit
    ) {
        val clone = source?.drawable?.constantState
            ?.newDrawable(host.resources)?.mutate()
        if (clone != null) {
            button.setImageDrawable(clone)
        } else {
            val icon = host.resources.getIdentifier(
                "ic_gm_more_vert", "drawable", host.context.packageName
            )
            if (icon != 0) button.setImageResource(icon)
        }
        button.imageTintList = source?.imageTintList
            ?: android.content.res.ColorStateList.valueOf(
                resolveTextColor(host)
            )
        button.contentDescription = source?.contentDescription
            ?: NativeGmmpUiText.string(host.context, "menu")
        button.visibility = View.VISIBLE
        button.isEnabled = true
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener { onContext(button) }
    }

    private fun findNativeAddItem(
        menu: android.view.Menu,
        context: android.content.Context
    ): android.view.MenuItem? {
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (!NativeResourceIdPolicy.canResolveEntryName(item.itemId)) {
                continue
            }
            val name = runCatching {
                context.resources.getResourceEntryName(item.itemId)
            }.getOrNull()
            if (name == "menuAdd") return item
        }
        return null
    }

    private fun findFirstTextView(view: View): TextView? {
        if (view is TextView) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findFirstTextView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun selectableBackground(
        host: View,
        borderless: Boolean = false
    ): Drawable? {
        val attrs = intArrayOf(
            if (borderless) {
                android.R.attr.selectableItemBackgroundBorderless
            } else {
                android.R.attr.selectableItemBackground
            }
        )
        val typed = host.context.obtainStyledAttributes(attrs)
        return try {
            typed.getDrawable(0)
        } finally {
            typed.recycle()
        }
    }

    private fun resolveTextColor(host: View): Int =
        resolveTextColor(host.context)

    private fun resolveTextColor(context: android.content.Context): Int {
        val typed = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        return try {
            typed.getColorStateList(0)?.getColorForState(
                intArrayOf(android.R.attr.state_enabled),
                typed.getColor(0, Color.WHITE)
            ) ?: typed.getColor(0, Color.WHITE)
        } finally {
            typed.recycle()
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private class FolderOutlineDrawable(
        color: Int,
        private val sizePx: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.25f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            val scale = minOf(
                b.width() / 24f,
                b.height() / 24f
            )
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scale, scale)
            val path = Path().apply {
                moveTo(3f, 5.5f)
                lineTo(9f, 5.5f)
                lineTo(11f, 8f)
                lineTo(21f, 8f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }
        override fun setColorFilter(
            colorFilter: android.graphics.ColorFilter?
        ) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int =
            android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }
}

internal class NativeFolderBreadcrumbAdapter(
    private val tag: String,
    private val host: View,
    private val onStyleLabel: (TextView) -> Unit,
    private val onSegmentClick: (PlaylistFolderUiKit.BreadcrumbSegment) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val segments =
        arrayListOf<PlaylistFolderUiKit.BreadcrumbSegment>()
    private var styleSignature = ""
    val appliedStyleSignature: String get() = styleSignature
    var pathRevision = 0L
        private set
    var rootClosing = false
    var rootOpening = false
    val rootTransitionPending: Boolean get() = rootClosing || rootOpening

    fun rootSegment(): PlaylistFolderUiKit.BreadcrumbSegment? = segments.firstOrNull()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int =
        NativeQuickNavDiff.itemCount(segments.size)

    override fun getItemViewType(position: Int): Int = position % 2

    override fun getItemId(position: Int): Long {
        // Native pg1 uses position for the separator and path hash for
        // metadata. Separate namespaces avoid a numeric collision while
        // retaining the exact position/key identity semantics.
        if (position % 2 != 0) return Long.MIN_VALUE + position
        return segments[position / 2].key.hashCode().toLong()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        val layoutName = if (viewType == 0) {
            "rv_horiz_metadata"
        } else {
            "rv_horiz_separator"
        }
        val layoutId = parent.resources.getIdentifier(
            layoutName, "layout", parent.context.packageName
        )
        val native = if (layoutId != 0) {
            runCatching {
                LayoutInflater.from(parent.context)
                    .inflate(layoutId, parent, false)
            }.getOrNull()
        } else null
        val view = native ?: if (viewType == 0) {
            TextView(parent.context).apply {
                minHeight = dp(parent, 48)
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            ImageView(parent.context).apply {
                val icon = resources.getIdentifier(
                    "ic_gm_keyboard_arrow_right",
                    "drawable",
                    context.packageName
                )
                if (icon != 0) setImageResource(icon)
            }
        }
        if (view.layoutParams == null) {
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int
    ) {
        if (position % 2 != 0) return
        val segment = segments.getOrNull(position / 2) ?: return
        val label = (holder.itemView as? TextView)
            ?: findTextView(holder.itemView)
            ?: return
        label.text = segment.label
        onStyleLabel(label)
        label.isClickable = true
        label.isFocusable = true
        label.setOnClickListener {
            onSegmentClick(segment)
        }
    }

    fun plan(next: List<PlaylistFolderUiKit.BreadcrumbSegment>): NativeQuickNavDiff.Plan =
        NativeQuickNavDiff.between(
            segments.map { it.key + "\u0000" + it.label },
            next.map { it.key + "\u0000" + it.label }
        )

    fun submit(
        next: List<PlaylistFolderUiKit.BreadcrumbSegment>,
        nextStyleSignature: String
    ): Boolean {
        val oldIds = segments.map { it.key + "\u0000" + it.label }
        val newIds = next.map { it.key + "\u0000" + it.label }
        val styleChanged = nextStyleSignature != styleSignature
        val pathChanged = oldIds != newIds
        if (pathChanged) {
            val oldItems = NativeQuickNavTransaction.items(oldIds)
            val newItems = NativeQuickNavTransaction.items(newIds)
            // wg1$f computes DiffUtil with detectMoves=false and qg1
            // compares each actual item by equality for both callbacks.
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    oldItems[oldItemPosition] == newItems[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    areItemsTheSame(oldItemPosition, newItemPosition)
            }, false)
            // Publish the COMPLETE new snapshot before dispatch, as mg1.t3
            // does. Never mutate the backing list between notifications.
            ++pathRevision
            segments.clear()
            segments.addAll(next)
            styleSignature = nextStyleSignature
            result.dispatchUpdatesTo(this)
        } else {
            styleSignature = nextStyleSignature
            if (styleChanged && itemCount > 0) {
                notifyItemRangeChanged(0, itemCount, "native-style")
            }
        }
        return pathChanged
    }

    private fun findTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()
}
