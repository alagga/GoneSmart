package io.github.alagga.gonesmart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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
            style.rowBackground?.newDrawable(host.resources)
                ?.mutate()?.let { template.background = it }
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
            background = selectableBackground(host)
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

    private fun selectableBackground(host: View): Drawable? {
        val attrs = intArrayOf(
            android.R.attr.selectableItemBackgroundBorderless
        )
        val typed = host.context.obtainStyledAttributes(attrs)
        return try {
            typed.getDrawable(0)
        } finally {
            typed.recycle()
        }
    }

    private fun resolveTextColor(host: View): Int {
        val typed = host.context.obtainStyledAttributes(
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

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int =
        NativeQuickNavDiff.itemCount(segments.size)

    override fun getItemViewType(position: Int): Int = position % 2

    override fun getItemId(position: Int): Long {
        val segment = segments[position / 2]
        return (segment.key.hashCode().toLong() shl 1) xor
            (position % 2).toLong()
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

    fun submit(
        next: List<PlaylistFolderUiKit.BreadcrumbSegment>,
        nextStyleSignature: String
    ): Boolean {
        val oldIds = segments.map { it.key + "\u0000" + it.label }
        val newIds = next.map { it.key + "\u0000" + it.label }
        val diff = NativeQuickNavDiff.between(oldIds, newIds)
        val styleChanged = nextStyleSignature != styleSignature
        val pathChanged = oldIds != newIds
        styleSignature = nextStyleSignature

        if (diff.removedCount > 0) {
            segments.subList(diff.sharedSegments, segments.size).clear()
            notifyItemRangeRemoved(diff.retainedItems, diff.removedCount)
        }
        if (diff.insertedCount > 0) {
            segments.addAll(next.drop(diff.sharedSegments))
            notifyItemRangeInserted(diff.retainedItems, diff.insertedCount)
        }
        if (styleChanged && itemCount > 0 && !pathChanged) {
            notifyItemRangeChanged(0, itemCount, "native-style")
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
