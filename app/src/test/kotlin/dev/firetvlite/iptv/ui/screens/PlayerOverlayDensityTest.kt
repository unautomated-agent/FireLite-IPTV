package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.ui.components.CLOCK_BACKGROUND_ALPHA
import dev.firetvlite.iptv.ui.components.CLOCK_CORNER_DP
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOverlayDensityTest {
    @Test fun compactChannelRowsFitMoreChannelsAndRetainRoomForLabelsAndLogo() {
        val densityGain = 88f / (CHANNEL_OVERLAY_ROW_DP + CHANNEL_OVERLAY_GAP_DP)
        assertTrue(densityGain in 1.5f..2f)
        assertTrue(CHANNEL_OVERLAY_LOGO_DP <= CHANNEL_OVERLAY_ROW_DP)
        assertTrue(18 + 16 + 3 + 4 <= CHANNEL_OVERLAY_ROW_DP)
    }

    @Test fun clockHasSofterBackgroundAndSmallerCorners() {
        assertTrue(CLOCK_BACKGROUND_ALPHA in 0.35f..0.45f)
        assertTrue(CLOCK_CORNER_DP in 1..7)
    }
}
