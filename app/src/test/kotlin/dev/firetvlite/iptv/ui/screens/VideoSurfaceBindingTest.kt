package dev.firetvlite.iptv.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoSurfaceBindingTest {
    @Test fun miniPlayerAndFullscreenResizeReattachTheSamePlayerInOrder() {
        val player = Any()
        val bindings = mutableListOf<Any?>()
        rebindVideoSurfaceOnResize(player, 228, 128, 1920, 1080, bindings::add)
        rebindVideoSurfaceOnResize(player, 1920, 1080, 228, 128, bindings::add)
        assertEquals(4, bindings.size)
        assertNull(bindings[0])
        assertSame(player, bindings[1])
        assertNull(bindings[2])
        assertSame(player, bindings[3])
    }

    @Test fun initialLayoutUnchangedSizeAndEmptyOrReleasedSurfacesDoNotRebind() {
        val bindings = mutableListOf<Any?>()
        val player = Any()
        rebindVideoSurfaceOnResize(player, 1920, 1080, 0, 0, bindings::add)
        rebindVideoSurfaceOnResize(player, 1920, 1080, 1920, 1080, bindings::add)
        rebindVideoSurfaceOnResize(player, 0, 0, 1920, 1080, bindings::add)
        rebindVideoSurfaceOnResize(player, 1920, 0, 1920, 1080, bindings::add)
        rebindVideoSurfaceOnResize(null, 1920, 1080, 228, 128, bindings::add)
        assertTrue(bindings.isEmpty())
    }

    @Test fun widthOnlyAndHeightOnlyChangesAlsoRebind() {
        val bindings = mutableListOf<Any?>()
        val player = Any()
        rebindVideoSurfaceOnResize(player, 1920, 720, 1920, 1080, bindings::add)
        rebindVideoSurfaceOnResize(player, 1280, 720, 1920, 720, bindings::add)
        assertEquals(listOf(null, player, null, player), bindings)
    }

    @Test fun retainedViewRebindsAfterLayoutForBothGuideAndPanelsWithoutStreamCommands() {
        val screen = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerScreen.kt").readText()
        val host = screen.substringAfter("        AndroidView(factory =").substringBefore("        if (mode == PlayerMode.Preview)")
        assertTrue(host.contains("addOnLayoutChangeListener"))
        assertTrue(host.contains("rebindVideoSurfaceOnResize(this.player"))
        assertTrue(host.contains("oldRight - oldLeft, oldBottom - oldTop) { this.player = it }"))
        assertTrue(screen.contains("mode == PlayerMode.Guide || mode == PlayerMode.Panel"))
        assertFalse(host.contains("prepare("))
        assertFalse(host.contains("play("))
        assertFalse(host.contains("stop("))
        val shell = File("src/main/kotlin/dev/firetvlite/iptv/FireLiteApp.kt").readText()
        assertEquals(1, Regex("PlayerLayer\\(").findAll(shell).count())
    }
}
