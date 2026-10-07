package io.github.alagga.gonesmart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The exact existing GoneSmart multi-playlist checkmark renderer, shared by
 * the native Add-picker confirm FAB and the new destination-move FAB.
 */
internal class PlaylistConfirmDrawable(
    private val iconSizePx: Int
) : Drawable() {
    override fun getIntrinsicWidth(): Int = iconSizePx
    override fun getIntrinsicHeight(): Int = iconSizePx

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val rect = bounds
        val w = rect.width().toFloat()
        val h = rect.height().toFloat()
        val x = rect.left.toFloat()
        val y = rect.top.toFloat()

        stroke.color = Color.WHITE
        stroke.strokeWidth = w * 0.115f
        canvas.drawLine(
            x + w * 0.15f, y + h * 0.54f,
            x + w * 0.42f, y + h * 0.78f,
            stroke
        )
        canvas.drawLine(
            x + w * 0.42f, y + h * 0.78f,
            x + w * 0.87f, y + h * 0.26f,
            stroke
        )

    // The shared Auto-DJ badge renderer draws the lilac sparkle as
    // a separate overlay in the top-right corner of the native FAB.
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
