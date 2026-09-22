package io.github.alagga.gonesmart

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayerAutoDjBadgeController {

    companion object {
        private const val TAG = "GoneSmart"
        private const val GREEN = 0xFF4CC96A.toInt()
        private const val RED = 0xFFFF5C68.toInt()
        private const val RESCAN_DEBOUNCE_MS = 180L
        private const val PLAYBACK_MODE_CHECK_MS = 550L

        // GMMP 4.2.0: visible only on the full Now Playing screen.
        // This is the upper button whose content description is
        // "Zur Warteschlange wechseln". It is only a screen marker.
        private const val NOW_PLAYING_MARKER_RESOURCE_NAME = "npMediaBtn10"

        // Confirmed by the user's Build 40 log. This is the playback-mode
        // button on the FAR RIGHT of the transport row, directly to the right
        // of Next Track. Its drawable changes between Shuffle / normal modes /
        // Auto-DJ (headphones). This is the only view that may receive a badge.
        private const val PLAYBACK_MODE_RESOURCE_NAME = "npMediaBtn4"
    }

    enum class Mode {
        NONE,
        SMART,
        FALLBACK
    }

    private data class GlyphAnalysis(
        val fingerprint: Int,
        val mirrorIou: Float,
        val topCenter: Float,
        val lowerCenter: Float,
        val lowerLeft: Float,
        val lowerRight: Float,
        val activeFraction: Float,
        val looksLikeHeadphones: Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentMode = Mode.NONE

    private var targetRef: WeakReference<View>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var observedDecorRef: WeakReference<View>? = null
    private var overlayDrawable: SparkleBadgeDrawable? = null
    private var loggedMissingTarget = false
    private var lastScanElapsedMs = Long.MIN_VALUE
    private var modeMonitorScheduled = false
    private var lastAutoDjDetection: Boolean? = null
    private var lastRenderedMode: Mode? = null
    private var lastGlyphFingerprint: Int? = null

    private val globalLayoutListener =
        ViewTreeObserver.OnGlobalLayoutListener {
            scheduleRescan()
        }

    private val modeMonitorRunnable = object : Runnable {
        override fun run() {
            modeMonitorScheduled = false

            val activity = activityRef?.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) {
                detachTarget()
                return
            }

            val target = targetRef?.get()
            if (
                target != null &&
                target.isAttachedToWindow &&
                target.isShown &&
                resourceName(target).equals(
                    PLAYBACK_MODE_RESOURCE_NAME,
                    ignoreCase = true
                ) &&
                isFullNowPlayingVisible(activity)
            ) {
                refreshBadge(target)
            } else {
                findAndAttach(activity, forceLog = false)
            }

            scheduleModeMonitor()
        }
    }

    fun setMode(mode: Mode) {
        currentMode = mode

        val activity = activityRef?.get()
        val target = targetRef?.get()

        if (
            activity != null &&
            target != null &&
            target.isAttachedToWindow &&
            target.isShown &&
            resourceName(target).equals(
                PLAYBACK_MODE_RESOURCE_NAME,
                ignoreCase = true
            ) &&
            isFullNowPlayingVisible(activity)
        ) {
            target.post { refreshBadge(target) }
        } else {
            activity?.let { attach(it) }
        }
    }

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)

        val decor = activity.window?.decorView ?: return
        installGlobalLayoutListener(decor)
        scheduleModeMonitor()

        decor.post { findAndAttach(activity, forceLog = false) }
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = false) }, 250L)
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = false) }, 900L)
        mainHandler.postDelayed({ findAndAttach(activity, forceLog = true) }, 1800L)
    }

    private fun scheduleModeMonitor() {
        if (modeMonitorScheduled) return
        modeMonitorScheduled = true
        mainHandler.postDelayed(modeMonitorRunnable, PLAYBACK_MODE_CHECK_MS)
    }

    private fun installGlobalLayoutListener(decor: View) {
        val previous = observedDecorRef?.get()
        if (previous === decor) {
            return
        }

        previous?.viewTreeObserver
            ?.takeIf { it.isAlive }
            ?.removeOnGlobalLayoutListener(globalLayoutListener)

        observedDecorRef = WeakReference(decor)

        decor.viewTreeObserver
            .takeIf { it.isAlive }
            ?.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun scheduleRescan() {
        val now = SystemClock.elapsedRealtime()
        if (
            lastScanElapsedMs != Long.MIN_VALUE &&
            now - lastScanElapsedMs < RESCAN_DEBOUNCE_MS
        ) {
            return
        }

        lastScanElapsedMs = now
        val activity = activityRef?.get() ?: return
        mainHandler.post { findAndAttach(activity, forceLog = false) }
    }

    private fun findAndAttach(
        activity: Activity,
        forceLog: Boolean
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            detachTarget()
            return
        }

        val decor = activity.window?.decorView ?: return
        val views = mutableListOf<View>()
        collectViews(decor, views)

        val nowPlayingVisible = views.any { view ->
            view.isShown &&
                view.width > 0 &&
                view.height > 0 &&
                resourceName(view).equals(
                    NOW_PLAYING_MARKER_RESOURCE_NAME,
                    ignoreCase = true
                )
        }

        if (!nowPlayingVisible) {
            detachTarget()
            if (forceLog && !loggedMissingTarget) {
                loggedMissingTarget = true
                Log.i(
                    TAG,
                    "PLAYER BADGE HIDDEN | full Now Playing screen is not visible"
                )
            }
            return
        }

        val target = views.firstOrNull { view ->
            view.isShown &&
                view.width > 0 &&
                view.height > 0 &&
                resourceName(view).equals(
                    PLAYBACK_MODE_RESOURCE_NAME,
                    ignoreCase = true
                )
        }

        if (target == null) {
            detachTarget()
            if (forceLog && !loggedMissingTarget) {
                loggedMissingTarget = true
                Log.w(
                    TAG,
                    "PLAYER BADGE PLAYBACK-MODE BUTTON NOT FOUND | " +
                        "expectedId=$PLAYBACK_MODE_RESOURCE_NAME"
                )
            }
            return
        }

        val existing = targetRef?.get()
        if (existing === target) {
            loggedMissingTarget = false
            updateOverlayBounds(target)
            refreshBadge(target)
            return
        }

        detachTarget()
        loggedMissingTarget = false
        targetRef = WeakReference(target)
        lastAutoDjDetection = null
        lastRenderedMode = null
        lastGlyphFingerprint = null

        Log.i(
            TAG,
            "PLAYER BADGE PLAYBACK-MODE TARGET ATTACHED | ${describeView(target)}"
        )

        refreshBadge(target)
    }

    private fun isFullNowPlayingVisible(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        val views = mutableListOf<View>()
        collectViews(decor, views)

        return views.any { view ->
            view.isShown &&
                resourceName(view).equals(
                    NOW_PLAYING_MARKER_RESOURCE_NAME,
                    ignoreCase = true
                )
        }
    }

    private fun resourceName(view: View): String {
        return try {
            if (view.id != View.NO_ID) {
                view.resources.getResourceEntryName(view.id)
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun refreshBadge(target: View) {
        val autoDjActive = isAutoDjPlaybackMode(target)

        if (lastAutoDjDetection != autoDjActive) {
            lastAutoDjDetection = autoDjActive
            Log.i(
                TAG,
                "PLAYER BADGE PLAYBACK MODE | " +
                    if (autoDjActive) {
                        "AUTO-DJ"
                    } else {
                        "NOT AUTO-DJ"
                    } +
                    " | ${describeView(target)}"
            )
        }

        val desiredMode =
            if (autoDjActive) {
                currentMode
            } else {
                Mode.NONE
            }

        if (
            desiredMode == lastRenderedMode &&
            ((desiredMode == Mode.NONE && overlayDrawable == null) ||
                (desiredMode != Mode.NONE && overlayDrawable != null))
        ) {
            updateOverlayBounds(target)
            return
        }

        clearOverlay()
        lastRenderedMode = desiredMode

        if (desiredMode == Mode.NONE) {
            return
        }

        val color = when (desiredMode) {
            Mode.SMART -> GREEN
            Mode.FALLBACK -> RED
            Mode.NONE -> return
        }

        val drawable = SparkleBadgeDrawable(color)
        overlayDrawable = drawable
        updateOverlayBounds(target, drawable)
        target.overlay.add(drawable)
        target.invalidate()
    }

    private fun isAutoDjPlaybackMode(target: View): Boolean {
        val labels = buildString {
            append(target.contentDescription?.toString().orEmpty())
            append(' ')
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                append(target.tooltipText?.toString().orEmpty())
            }
            append(' ')
            append(target.tag?.toString().orEmpty())
        }.lowercase()

        // A positive Auto-DJ label is trustworthy. A "Shuffle" label is NOT:
        // GMMP 4.2.0 keeps the static accessibility description
        // "Shuffle ein-/ ausschalten" on npMediaBtn4 even while the actual
        // icon has changed to Auto-DJ headphones. Therefore negative labels
        // must not short-circuit drawable inspection.
        if (
            labels.contains("auto-dj") ||
            labels.contains("auto dj") ||
            labels.contains("autodj")
        ) {
            return true
        }

        val imageView = target as? ImageView ?: return false
        val drawable = imageView.drawable ?: return false
        val analysis = analyzeGlyph(drawable)

        if (lastGlyphFingerprint != analysis.fingerprint) {
            lastGlyphFingerprint = analysis.fingerprint
            Log.i(
                TAG,
                "PLAYER BADGE GLYPH | " +
                    "autoDj=${analysis.looksLikeHeadphones} | " +
                    "mirror=${format(analysis.mirrorIou)} | " +
                    "top=${format(analysis.topCenter)} | " +
                    "lowerCenter=${format(analysis.lowerCenter)} | " +
                    "lowerLeft=${format(analysis.lowerLeft)} | " +
                    "lowerRight=${format(analysis.lowerRight)} | " +
                    "active=${format(analysis.activeFraction)} | " +
                    "fp=${analysis.fingerprint}"
            )
        }

        return analysis.looksLikeHeadphones
    }

    private fun analyzeGlyph(drawable: Drawable): GlyphAnalysis {
        val size = 64
        val bitmap = Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val originalBounds = Rect(drawable.bounds)

        try {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        } catch (_: Throwable) {
            try {
                drawable.bounds = originalBounds
            } catch (_: Throwable) {
            }
            bitmap.recycle()
            return GlyphAnalysis(
                fingerprint = 0,
                mirrorIou = 0f,
                topCenter = 0f,
                lowerCenter = 1f,
                lowerLeft = 0f,
                lowerRight = 0f,
                activeFraction = 0f,
                looksLikeHeadphones = false
            )
        } finally {
            try {
                drawable.bounds = originalBounds
            } catch (_: Throwable) {
            }
        }

        val pixels = IntArray(size * size)
        bitmap.getPixels(
            pixels,
            0,
            size,
            0,
            0,
            size,
            size
        )
        bitmap.recycle()

        fun active(x: Int, y: Int): Boolean {
            val alpha = pixels[y * size + x] ushr 24 and 0xFF
            return alpha >= 40
        }

        var minX = size
        var minY = size
        var maxX = -1
        var maxY = -1
        var globalActive = 0
        var fingerprint = 17

        for (y in 0 until size) {
            for (x in 0 until size) {
                if (active(x, y)) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    globalActive++
                    fingerprint = fingerprint * 31 + (y * size + x)
                }
            }
        }

        if (maxX <= minX || maxY <= minY || globalActive <= 0) {
            return GlyphAnalysis(
                fingerprint = fingerprint,
                mirrorIou = 0f,
                topCenter = 0f,
                lowerCenter = 1f,
                lowerLeft = 0f,
                lowerRight = 0f,
                activeFraction = 0f,
                looksLikeHeadphones = false
            )
        }

        val width = maxX - minX + 1
        val height = maxY - minY + 1

        var mirrorIntersection = 0
        var mirrorUnion = 0

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val mirroredX = maxX - (x - minX)
                val a = active(x, y)
                val b = active(mirroredX, y)
                if (a && b) mirrorIntersection++
                if (a || b) mirrorUnion++
            }
        }

        val mirrorIou =
            if (mirrorUnion > 0) {
                mirrorIntersection.toFloat() / mirrorUnion.toFloat()
            } else {
                0f
            }

        fun occupancy(
            xStartFraction: Float,
            xEndFraction: Float,
            yStartFraction: Float,
            yEndFraction: Float
        ): Float {
            val xStart =
                (minX + width * xStartFraction)
                    .toInt()
                    .coerceIn(minX, maxX)
            val xEnd =
                (minX + width * xEndFraction)
                    .toInt()
                    .coerceIn(minX + 1, maxX + 1)
            val yStart =
                (minY + height * yStartFraction)
                    .toInt()
                    .coerceIn(minY, maxY)
            val yEnd =
                (minY + height * yEndFraction)
                    .toInt()
                    .coerceIn(minY + 1, maxY + 1)

            var activeCount = 0
            var totalCount = 0

            for (y in yStart until yEnd) {
                for (x in xStart until xEnd) {
                    if (active(x, y)) activeCount++
                    totalCount++
                }
            }

            return if (totalCount > 0) {
                activeCount.toFloat() / totalCount.toFloat()
            } else {
                0f
            }
        }

        val topCenter = occupancy(
            0.25f,
            0.75f,
            0.00f,
            0.46f
        )
        val lowerCenter = occupancy(
            0.38f,
            0.62f,
            0.48f,
            0.95f
        )
        val lowerLeft = occupancy(
            0.00f,
            0.34f,
            0.46f,
            1.00f
        )
        val lowerRight = occupancy(
            0.66f,
            1.00f,
            0.46f,
            1.00f
        )

        val activeFraction =
            globalActive.toFloat() /
                (width * height).coerceAtLeast(1).toFloat()

        val sideAverage =
            (lowerLeft + lowerRight) / 2f
        val sideBalance =
            abs(lowerLeft - lowerRight)

        // The headphones glyph has three strong traits:
        // 1) it is approximately left/right symmetric,
        // 2) the arch occupies the upper centre,
        // 3) the two lower side earcups are denser than the lower centre.
        // The thresholds are intentionally wider than Build 40's first attempt.
        val looksLikeHeadphones =
            mirrorIou >= 0.58f &&
                topCenter >= 0.035f &&
                sideAverage >= 0.10f &&
                lowerCenter <= 0.26f &&
                sideAverage >= lowerCenter * 1.20f &&
                sideBalance <= 0.16f &&
                activeFraction in 0.08f..0.62f

        return GlyphAnalysis(
            fingerprint = fingerprint,
            mirrorIou = mirrorIou,
            topCenter = topCenter,
            lowerCenter = lowerCenter,
            lowerLeft = lowerLeft,
            lowerRight = lowerRight,
            activeFraction = activeFraction,
            looksLikeHeadphones = looksLikeHeadphones
        )
    }

    private fun updateOverlayBounds(target: View) {
        overlayDrawable?.let { updateOverlayBounds(target, it) }
    }

    private fun updateOverlayBounds(
        target: View,
        drawable: SparkleBadgeDrawable
    ) {
        drawable.setBounds(0, 0, target.width, target.height)
    }

    private fun detachTarget() {
        clearOverlay()
        targetRef = null
        lastAutoDjDetection = null
        lastRenderedMode = null
        lastGlyphFingerprint = null
    }

    private fun clearOverlay() {
        val previousTarget = targetRef?.get()
        val drawable = overlayDrawable

        if (previousTarget != null && drawable != null) {
            try {
                previousTarget.overlay.remove(drawable)
            } catch (_: Throwable) {
            }
        }

        overlayDrawable = null
    }

    private fun collectViews(
        view: View,
        output: MutableList<View>
    ) {
        output += view

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectViews(view.getChildAt(index), output)
            }
        }
    }

    private fun describeView(view: View): String {
        val resourceName = resourceName(view).ifBlank { "no-id" }
        val location = IntArray(2)
        view.getLocationOnScreen(location)

        return "class=${view.javaClass.simpleName} " +
            "id=$resourceName " +
            "desc=${view.contentDescription} " +
            "clickable=${view.isClickable} " +
            "size=${view.width}x${view.height} " +
            "screen=${location[0]},${location[1]}"
    }

    private fun format(value: Float): String {
        return String.format(java.util.Locale.US, "%.3f", value)
    }

    internal class SparkleBadgeDrawable(
        private val badgeColor: Int
    ) : Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = badgeColor
        }

        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = lighten(badgeColor, 0.58f)
        }

        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private var drawableAlpha = 255
        private var currentColorFilter: android.graphics.ColorFilter? = null

        override fun draw(canvas: Canvas) {
            val size = min(bounds.width(), bounds.height()).toFloat()
            if (size <= 0f) return

            val centerX = (bounds.left + bounds.right) / 2f
            val centerY = (bounds.top + bounds.bottom) / 2f

            /*
             * The badge deliberately stays compact and close to the Auto-DJ
             * headphones glyph. The soft radial halo gives separation from
             * light/dark album-art themes without a cheap-looking black edge.
             */
            val radius = size * 0.105f
            val cx = centerX + size * 0.17f
            val cy = centerY - size * 0.17f

            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = RadialGradient(
                    cx,
                    cy,
                    radius * 1.72f,
                    intArrayOf(
                        withAlpha(badgeColor, scaledAlpha(88)),
                        withAlpha(badgeColor, scaledAlpha(38)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0.0f, 0.46f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                colorFilter = currentColorFilter
            }
            canvas.drawCircle(
                cx,
                cy,
                radius * 1.72f,
                glowPaint
            )

            fillPaint.alpha = drawableAlpha
            highlightPaint.alpha = scaledAlpha(235)
            corePaint.alpha = scaledAlpha(210)
            fillPaint.colorFilter = currentColorFilter
            highlightPaint.colorFilter = currentColorFilter
            corePaint.colorFilter = currentColorFilter

            drawCurvedSparkle(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = radius,
                paint = fillPaint
            )

            /* Small luminous centre rather than an outline. */
            drawCurvedSparkle(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = radius * 0.31f,
                paint = corePaint
            )

            /* A restrained secondary sparkle adds depth without clutter. */
            drawCurvedSparkle(
                canvas = canvas,
                cx = cx - radius * 0.88f,
                cy = cy + radius * 0.88f,
                radius = radius * 0.38f,
                paint = highlightPaint
            )
        }

        private fun drawCurvedSparkle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            paint: Paint
        ) {
            val waist = radius * 0.16f
            val curve = radius * 0.52f

            val path = Path().apply {
                moveTo(cx, cy - radius)

                cubicTo(
                    cx + waist * 0.18f,
                    cy - curve,
                    cx + curve,
                    cy - waist * 0.18f,
                    cx + radius,
                    cy
                )

                cubicTo(
                    cx + curve,
                    cy + waist * 0.18f,
                    cx + waist * 0.18f,
                    cy + curve,
                    cx,
                    cy + radius
                )

                cubicTo(
                    cx - waist * 0.18f,
                    cy + curve,
                    cx - curve,
                    cy + waist * 0.18f,
                    cx - radius,
                    cy
                )

                cubicTo(
                    cx - curve,
                    cy - waist * 0.18f,
                    cx - waist * 0.18f,
                    cy - curve,
                    cx,
                    cy - radius
                )

                close()
            }

            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            currentColorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun scaledAlpha(alpha: Int): Int {
            return (
                alpha.coerceIn(0, 255) *
                    drawableAlpha /
                    255
                )
                .coerceIn(0, 255)
        }

        companion object {
            private fun withAlpha(color: Int, alpha: Int): Int {
                return Color.argb(
                    alpha.coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }

            private fun lighten(color: Int, amount: Float): Int {
                val clamped = amount.coerceIn(0f, 1f)

                fun channel(value: Int): Int {
                    return (
                        value +
                            (255 - value) * clamped
                        )
                        .toInt()
                        .coerceIn(0, 255)
                }

                return Color.rgb(
                    channel(Color.red(color)),
                    channel(Color.green(color)),
                    channel(Color.blue(color))
                )
            }
        }
    }

}
