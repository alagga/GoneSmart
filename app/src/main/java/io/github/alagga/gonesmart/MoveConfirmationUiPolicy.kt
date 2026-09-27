package io.github.alagga.gonesmart

/**
 * Pure UI policy for the DEBUG playlist-move destination browser.
 * Dynamic native GMMP Aesthetic values are supplied by the controller;
 * this class never invents or caches a theme color.
 */
internal object MoveConfirmationUiPolicy {
    fun contentBottomInset(
        active: Boolean,
        measuredBarHeightPx: Int,
        fallbackHeightPx: Int
    ): Int = if (!active) {
        0
    } else {
        measuredBarHeightPx.takeIf { it > 0 }
            ?: fallbackHeightPx.coerceAtLeast(0)
    }

    /**
     * Choose the text with the greater WCAG contrast against the ACTUAL
     * current GMMP accent. Color math is Android-independent for unit tests.
     */
    fun textColorForBackground(argb: Int): Int {
        fun linearChannel(shift: Int): Double {
            val value = ((argb ushr shift) and 0xff) / 255.0
            return if (value <= 0.04045) {
                value / 12.92
            } else {
                Math.pow((value + 0.055) / 1.055, 2.4)
            }
        }
        val luminance = 0.2126 * linearChannel(16) +
            0.7152 * linearChannel(8) +
            0.0722 * linearChannel(0)
        val againstWhite = 1.05 / (luminance + 0.05)
        val againstBlack = (luminance + 0.05) / 0.05
        return if (againstWhite >= againstBlack) {
            0xffffffff.toInt()
        } else {
            0xff000000.toInt()
        }
    }
}
