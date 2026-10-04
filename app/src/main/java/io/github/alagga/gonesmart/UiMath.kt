package io.github.alagga.gonesmart

/** Small package-local UI math helpers used by the companion views. */
internal fun Float.roundToInt(): Int =
    kotlin.math.floor(toDouble() + 0.5).toInt()
