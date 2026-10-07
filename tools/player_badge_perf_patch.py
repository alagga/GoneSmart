from pathlib import Path


path = Path("app/src/main/java/io/github/alagga/gonesmart/PlayerAutoDjBadgeController.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''        private const val RESCAN_DEBOUNCE_MS = 180L
        private const val PLAYBACK_MODE_CHECK_MS = 550L
''',
    '''        private const val RESCAN_DEBOUNCE_MS = 180L
        private const val PLAYBACK_MODE_CHECK_MS = 550L
        private const val GLYPH_SAFETY_RECHECK_MS = 2000L
''',
    "badge timing constants",
)

replace_once(
    '''    private data class GlyphAnalysis(
        val fingerprint: Int,
        val mirrorIou: Float,
        val topCenter: Float,
        val lowerCenter: Float,
        val lowerLeft: Float,
        val lowerRight: Float,
        val activeFraction: Float,
        val looksLikeHeadphones: Boolean
    )
''',
    '''    private data class GlyphAnalysis(
        val fingerprint: Int,
        val mirrorIou: Float,
        val topCenter: Float,
        val lowerCenter: Float,
        val lowerLeft: Float,
        val lowerRight: Float,
        val activeFraction: Float,
        val looksLikeHeadphones: Boolean
    )

    private data class GlyphCacheKey(
        val drawableIdentity: Int,
        val stateHash: Int,
        val level: Int,
        val intrinsicWidth: Int,
        val intrinsicHeight: Int
    )
''',
    "glyph cache key",
)

replace_once(
    '''    private var targetRef: WeakReference<View>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var observedDecorRef: WeakReference<View>? = null
    private var overlayDrawable: SparkleBadgeDrawable? = null
    private var loggedMissingTarget = false
    private var lastScanElapsedMs = Long.MIN_VALUE
    private var modeMonitorScheduled = false
    private var lastAutoDjDetection: Boolean? = null
    private var lastRenderedMode: Mode? = null
    private var lastGlyphFingerprint: Int? = null
''',
    '''    private var targetRef: WeakReference<View>? = null
    private var nowPlayingMarkerRef: WeakReference<View>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var observedDecorRef: WeakReference<View>? = null
    private var overlayDrawable: SparkleBadgeDrawable? = null
    private var loggedMissingTarget = false
    private var lastScanElapsedMs = Long.MIN_VALUE
    private var modeMonitorScheduled = false
    private var lastAutoDjDetection: Boolean? = null
    private var lastRenderedMode: Mode? = null
    private var lastGlyphCacheKey: GlyphCacheKey? = null
    private var lastGlyphAnalysis: GlyphAnalysis? = null
    private var lastGlyphAnalysisElapsedMs = Long.MIN_VALUE
''',
    "badge controller cache state",
)

replace_once(
    '''            val target = targetRef?.get()
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
''',
    '''            val target = targetRef?.get()
            val marker = nowPlayingMarkerRef?.get()
            if (
                target != null &&
                marker != null &&
                isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
                isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
            ) {
                refreshBadge(target)
            } else {
                findAndAttach(activity, forceLog = false)
            }
''',
    "mode monitor cached views",
)

replace_once(
    '''        val activity = activityRef?.get()
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
''',
    '''        val activity = activityRef?.get()
        val target = targetRef?.get()
        val marker = nowPlayingMarkerRef?.get()

        if (
            activity != null &&
            target != null &&
            marker != null &&
            isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
            isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
        ) {
            target.post { refreshBadge(target) }
        } else {
            activity?.let { attach(it) }
        }
''',
    "set mode cached views",
)

replace_once(
    '''        lastScanElapsedMs = now
        val activity = activityRef?.get() ?: return
        mainHandler.post { findAndAttach(activity, forceLog = false) }
''',
    '''        lastScanElapsedMs = now
        val activity = activityRef?.get() ?: return
        val target = targetRef?.get()
        val marker = nowPlayingMarkerRef?.get()

        // Global layout can fire continuously while ViewPager tabs animate.
        // If the already-proven native anchors are still valid, there is no
        // reason to rebuild a full decor-tree inventory. Geometry alone may
        // have changed, while the 550 ms mode monitor owns glyph detection.
        if (
            target != null &&
            marker != null &&
            isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME) &&
            isUsableNativeView(marker, NOW_PLAYING_MARKER_RESOURCE_NAME)
        ) {
            mainHandler.post {
                if (isUsableNativeView(target, PLAYBACK_MODE_RESOURCE_NAME)) {
                    updateOverlayBounds(target)
                }
            }
            return
        }

        mainHandler.post { findAndAttach(activity, forceLog = false) }
''',
    "global-layout fast path",
)

replace_once(
    '''        val nowPlayingVisible = views.any { view ->
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
''',
    '''        val nowPlayingMarker = views.firstOrNull { view ->
            isUsableNativeView(view, NOW_PLAYING_MARKER_RESOURCE_NAME)
        }

        if (nowPlayingMarker == null) {
            detachTarget()
            loggedMissingTarget = false
            return
        }
''',
    "now playing marker lookup",
)

replace_once(
    '''        val target = views.firstOrNull { view ->
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
''',
    '''        val target = views.firstOrNull { view ->
            isUsableNativeView(view, PLAYBACK_MODE_RESOURCE_NAME)
        }

        if (target == null) {
            detachTarget()
            nowPlayingMarkerRef = WeakReference(nowPlayingMarker)
            if (forceLog && !loggedMissingTarget) {
''',
    "playback target lookup",
)

replace_once(
    '''        val existing = targetRef?.get()
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
''',
    '''        val existing = targetRef?.get()
        if (existing === target) {
            loggedMissingTarget = false
            nowPlayingMarkerRef = WeakReference(nowPlayingMarker)
            updateOverlayBounds(target)
            refreshBadge(target)
            return
        }

        detachTarget()
        loggedMissingTarget = false
        nowPlayingMarkerRef = WeakReference(nowPlayingMarker)
        targetRef = WeakReference(target)
        lastAutoDjDetection = null
        lastRenderedMode = null
        clearGlyphAnalysisCache()

        refreshBadge(target)
    }

    private fun isUsableNativeView(view: View?, expectedResourceName: String): Boolean {
        return view != null &&
            view.isAttachedToWindow &&
            view.isShown &&
            view.width > 0 &&
            view.height > 0 &&
            resourceName(view).equals(expectedResourceName, ignoreCase = true)
    }
''',
    "cached native anchor lifecycle",
)

replace_once(
    '''        if (lastAutoDjDetection != autoDjActive) {
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
''',
    '''        if (lastAutoDjDetection != autoDjActive) {
            lastAutoDjDetection = autoDjActive
        }
''',
    "playback mode info log",
)

replace_once(
    '''        val imageView = target as? ImageView ?: return false
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
''',
    '''        val imageView = target as? ImageView ?: return false
        val drawable = imageView.drawable ?: return false
        val cacheKey = GlyphCacheKey(
            drawableIdentity = System.identityHashCode(drawable),
            stateHash = drawable.state.contentHashCode(),
            level = drawable.level,
            intrinsicWidth = drawable.intrinsicWidth,
            intrinsicHeight = drawable.intrinsicHeight
        )
        val now = SystemClock.elapsedRealtime()
        val cached = lastGlyphAnalysis
        val canReuse =
            cached != null &&
                lastGlyphCacheKey == cacheKey &&
                lastGlyphAnalysisElapsedMs != Long.MIN_VALUE &&
                now - lastGlyphAnalysisElapsedMs < GLYPH_SAFETY_RECHECK_MS
        val analysis = if (canReuse) {
            cached!!
        } else {
            analyzeGlyph(drawable).also {
                lastGlyphCacheKey = cacheKey
                lastGlyphAnalysis = it
                lastGlyphAnalysisElapsedMs = now
            }
        }

        return analysis.looksLikeHeadphones
    }
''',
    "glyph analysis cache",
)

replace_once(
    '''    private fun detachTarget() {
        clearOverlay()
        targetRef = null
        lastAutoDjDetection = null
        lastRenderedMode = null
        lastGlyphFingerprint = null
    }
''',
    '''    private fun detachTarget() {
        clearOverlay()
        targetRef = null
        nowPlayingMarkerRef = null
        lastAutoDjDetection = null
        lastRenderedMode = null
        clearGlyphAnalysisCache()
    }

    private fun clearGlyphAnalysisCache() {
        lastGlyphCacheKey = null
        lastGlyphAnalysis = null
        lastGlyphAnalysisElapsedMs = Long.MIN_VALUE
    }
''',
    "detach cache reset",
)

replace_once(
    '''        private var drawableAlpha = 255
        private var currentColorFilter: android.graphics.ColorFilter? = null
''',
    '''        private var drawableAlpha = 255
        private var currentColorFilter: android.graphics.ColorFilter? = null
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val sparklePath = Path()
        private var glowCx = Float.NaN
        private var glowCy = Float.NaN
        private var glowRadius = Float.NaN
        private var glowColor = 0
        private var glowAlpha = -1
''',
    "drawable reusable render objects",
)

replace_once(
    '''            fillPaint.color = color
            highlightPaint.color = lighten(color, 0.58f)
            invalidateSelf()
''',
    '''            fillPaint.color = color
            highlightPaint.color = lighten(color, 0.58f)
            invalidateGlowShader()
            invalidateSelf()
''',
    "badge color glow invalidation",
)

replace_once(
    '''            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
''',
    '''            val haloRadius = radius * 1.72f
            if (glowPaint.shader == null ||
                glowCx != cx ||
                glowCy != cy ||
                glowRadius != haloRadius ||
                glowColor != badgeColor ||
                glowAlpha != drawableAlpha
            ) {
                glowCx = cx
                glowCy = cy
                glowRadius = haloRadius
                glowColor = badgeColor
                glowAlpha = drawableAlpha
                glowPaint.shader = RadialGradient(
                    cx,
                    cy,
                    haloRadius,
                    intArrayOf(
                        withAlpha(badgeColor, scaledAlpha(88)),
                        withAlpha(badgeColor, scaledAlpha(38)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0.0f, 0.46f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            glowPaint.colorFilter = currentColorFilter
''',
    "cached glow shader",
)

replace_once(
    '''                radius * 1.72f,
                glowPaint
''',
    '''                haloRadius,
                glowPaint
''',
    "cached halo radius draw",
)

replace_once(
    '''            val path = Path().apply {
                moveTo(cx, cy - radius)
''',
    '''            val path = sparklePath.apply {
                reset()
                moveTo(cx, cy - radius)
''',
    "reusable sparkle path",
)

replace_once(
    '''        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }
''',
    '''        override fun setAlpha(alpha: Int) {
            drawableAlpha = alpha.coerceIn(0, 255)
            invalidateGlowShader()
            invalidateSelf()
        }
''',
    "alpha glow invalidation",
)

replace_once(
    '''        private fun scaledAlpha(alpha: Int): Int {
''',
    '''        private fun invalidateGlowShader() {
            glowPaint.shader = null
            glowCx = Float.NaN
            glowCy = Float.NaN
            glowRadius = Float.NaN
            glowAlpha = -1
        }

        private fun scaledAlpha(alpha: Int): Int {
''',
    "glow shader invalidator",
)

path.write_text(text)
print("player badge hot-path cleanup applied")
