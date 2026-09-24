package io.github.alagga.gonesmart

/**
 * Match GMMP's native playlist title typography across playlist-list skins.
 * The compact metadataTextEntry happens to report a much smaller 30px
 * metadata size even when GMMP presents its visible list title at full
 * size. Apply the correction ONLY to this known compact metadata field;
 * any other title view retains GMMP's own live text size verbatim.
 */
internal object NativePlaylistRowTypography {
    fun titleSizePx(
        nativeTextPx: Float,
        nativeRowHeightPx: Int,
        titleResourceName: String
    ): Float {
        if (nativeTextPx <= 0f || nativeRowHeightPx <= 0) {
            return nativeTextPx
        }
        val isCompactMetadata =
            titleResourceName == "metadataTextEntry" &&
                nativeTextPx < nativeRowHeightPx * 0.28f
        return if (isCompactMetadata) {
            maxOf(nativeTextPx * 1.5f, nativeRowHeightPx * 0.31f)
        } else nativeTextPx
    }
}
