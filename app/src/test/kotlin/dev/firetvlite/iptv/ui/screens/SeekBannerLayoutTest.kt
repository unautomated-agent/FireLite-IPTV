package dev.firetvlite.iptv.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SeekBannerLayoutTest {
    @Test fun seekTargetReusesTimeRowWithoutAddingPreviewOrTargetRows() {
        val banner = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerInfoBanner.kt").readText()
        assertFalse(banner.contains("player_seek_preview"))
        assertFalse(banner.contains("seekPreview"))
        assertFalse(banner.contains("seekTargetMs?.let { FLText"))
        assertTrue(banner.contains("FLText(seekTargetMs?.let"))
        assertTrue(banner.contains("R.string.player_seek_target"))
        assertTrue(banner.contains("liveProgress = seekTargetMs?.let"))
        assertTrue(banner.contains("maxLines = 1)"))
    }
}
