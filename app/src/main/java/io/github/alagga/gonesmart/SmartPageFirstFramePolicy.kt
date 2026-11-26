package io.github.alagga.gonesmart

/**
 * A verified native os4 fragment may be the topmost visible page even while
 * GoneSmart deliberately holds its outer root alpha at zero. Its nested ls4
 * RecyclerView must remain eligible to finish projection and release the
 * outer gate. Any unrelated alpha=0 fragment remains non-foreground.
 */
internal object SmartPageFirstFramePolicy {
    fun mayBeFront(alpha: Float, ownsPendingPageMask: Boolean): Boolean =
        alpha > 0.01f || ownsPendingPageMask
}
