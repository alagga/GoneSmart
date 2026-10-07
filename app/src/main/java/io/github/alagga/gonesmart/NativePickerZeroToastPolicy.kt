package io.github.alagga.gonesmart

/**
 * One-shot, bounded suppression of the original GMMP 0-file Toast after
 * intentional create-only. The ONLY eligible text is GMMP's CURRENT
 * localized add_to_playlist_toast formatted with zero files. No custom
 * translations, generic "0" matching or permanent toast interception.
 */
internal class NativePickerZeroToastPolicy(
    private val windowMs: Long = 15_000L
) {
    private var pending: Int = 0
    private var expiresAt: Long = 0L

    fun arm(now: Long) {
        if (now > expiresAt) pending = 0
        pending = (pending + 1).coerceAtMost(4)
        expiresAt = now + windowMs
    }

    fun hasPending(now: Long): Boolean {
        if (now > expiresAt) pending = 0
        return pending > 0
    }

    fun shouldSuppress(
        actual: String,
        localizedEmptyResult: String,
        now: Long,
        insideCreate: Boolean
    ): Boolean {
        if (localizedEmptyResult.isBlank() ||
            actual != localizedEmptyResult
        ) return false
        if (!insideCreate && !hasPending(now)) return false
        if (hasPending(now)) pending--
        return true
    }
}
