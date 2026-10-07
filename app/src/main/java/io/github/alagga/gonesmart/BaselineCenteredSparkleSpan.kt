package io.github.alagga.gonesmart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * Single native-baseline lilac two-star mark for GMMP's own menu
 * rows and navigation drawer labels. Previously the private
 * QueueFlipController span; its exact metrics/draw are unchanged.
 */
/**
 * Full-size 28 dp GoneSmart two-star badge, centered in the native
 * menu row without altering TextView font metrics.
 */
internal class BaselineCenteredSparkleSpan(
    context: Context,
    private val badge: PlayerAutoDjBadgeController.SparkleBadgeDrawable
) : ReplacementSpan() {
    private val density = context.resources.displayMetrics.density

    private fun badgeSize(): Int =
        (28f * density).roundToInt().coerceAtLeast(1)

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = badgeSize() + (3f * density).roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val size = badgeSize()
        val metrics = paint.fontMetricsInt
        val fontCenter = y + (metrics.ascent + metrics.descent) / 2f
        badge.setBounds(0, 0, size, size)
        val saveCount = canvas.save()
        canvas.translate(x, fontCenter - size / 2f)
        badge.draw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
