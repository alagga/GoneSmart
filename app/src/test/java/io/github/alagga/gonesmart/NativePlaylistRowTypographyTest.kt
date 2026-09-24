package io.github.alagga.gonesmart

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePlaylistRowTypographyTest {
    @Test fun compactNativeMetadataUsesFullSizedPlaylistTitle() {
        assertEquals(
            45f,
            NativePlaylistRowTypography.titleSizePx(
                nativeTextPx = 30f,
                nativeRowHeightPx = 144,
                titleResourceName = "metadataTextEntry"
            ),
            0.001f
        )
    }

    @Test fun realNativeHeadlineKeepsExactSize() {
        assertEquals(
            46f,
            NativePlaylistRowTypography.titleSizePx(
                46f, 144, "playlistTitle"
            ),
            0.001f
        )
    }

    @Test fun differentGmmpSkinKeepsItsOwnTextSize() {
        assertEquals(
            32f,
            NativePlaylistRowTypography.titleSizePx(
                32f, 96, "metadataTextEntry"
            ),
            0.001f
        )
    }

    @Test fun compactRowRespectsScaledNativeTextSize() {
        assertEquals(
            54f,
            NativePlaylistRowTypography.titleSizePx(
                36f, 170, "metadataTextEntry"
            ),
            0.001f
        )
    }
}
