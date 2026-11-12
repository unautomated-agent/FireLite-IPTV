package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.ui.navigation.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class StreamNavigationTest {
    @Test fun rootBackDefersToSystemWithoutPlaybackEvenAfterPlaylistImport() {
        assertTrue(canChooseChannel(true, 20))
        for (mode in listOf(PlayerMode.Closed, PlayerMode.Panel, PlayerMode.Guide)) {
            assertFalse(screenBackReturnsToPlayback(mode, false))
        }
        assertFalse(screenBackReturnsToPlayback(PlayerMode.Closed, true))
        assertTrue(screenBackReturnsToPlayback(PlayerMode.Panel, true))
        assertTrue(screenBackReturnsToPlayback(PlayerMode.Guide, true))
        val shell = File("src/main/kotlin/dev/firetvlite/iptv/FireLiteApp.kt").readText()
        val backHandler = shell.substringAfter("    BackHandler(").substringBefore("\n    }")
        assertTrue(backHandler.contains("screenBackReturnsToPlayback(mode, channel != null)"))
        assertFalse(backHandler.contains("openGuide()"))
        val player = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerScreen.kt").readText()
        assertTrue(player.contains("BackHandler(enabled = mode.fullFrame || (mode == PlayerMode.Guide && screenBackReturnsToPlayback(mode, channel != null)))"))
    }

    @Test fun quickMenuHasExactlyTheRequestedActionsInOrderForBothFavoriteStates() {
        val actions = listOf(QuickMenuAction.Audio, QuickMenuAction.Favorite, QuickMenuAction.Guide,
            QuickMenuAction.Films, QuickMenuAction.Settings)
        assertEquals(actions, quickMenuItems(false).map { it.first })
        assertEquals(actions, quickMenuItems(true).map { it.first })
        assertEquals(listOf(R.string.quick_menu_audio, R.string.quick_menu_add_favorite, R.string.nav_guide,
            R.string.nav_films, R.string.nav_settings), quickMenuItems(false).map { it.second })
        assertEquals(R.string.quick_menu_remove_favorite, quickMenuItems(true)[1].second)
    }

    @Test fun noResumableChannelOpensSettingsRegardlessOfPreviousPanel() {
        for (panel in StreamPanel.entries) assertEquals(StreamPanel.Settings, visiblePanel(PlayerMode.Closed, panel))
        assertEquals(StreamPanel.Films, visiblePanel(PlayerMode.Panel, StreamPanel.Films))
        assertEquals(StreamPanel.Settings, visiblePanel(PlayerMode.Panel, StreamPanel.Settings))
        for (mode in listOf(PlayerMode.Fullscreen, PlayerMode.Overlay, PlayerMode.Guide)) {
            assertNull(visiblePanel(mode, StreamPanel.Settings))
        }
    }

    @Test fun backFromScreensReturnsToExistingPlaybackWithoutANavigationStack() {
        for (mode in listOf(PlayerMode.Guide, PlayerMode.Panel)) {
            assertEquals(PlayerMode.Fullscreen, mode.afterScreen(true))
            assertEquals(PlayerMode.Closed, mode.afterScreen(false))
            assertTrue(mode.continuesPlayback)
        }
        assertEquals(PlayerMode.Fullscreen, PlayerMode.Overlay.afterBack())
    }

    @Test fun playlistSetupExposesChannelSelectionOnlyAfterAUsableImport() {
        assertFalse(canChooseChannel(false, 0))
        assertFalse(canChooseChannel(false, 20))
        assertFalse(canChooseChannel(true, 0))
        assertTrue(canChooseChannel(true, 20))
        val settings = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/SettingsScreen.kt").readText()
        assertTrue(settings.indexOf("        SourceSection(") < settings.indexOf("R.string.settings_appearance"))
        assertTrue(settings.contains("if (canWatch) PrimaryButton(stringResource(R.string.settings_watch_live), onWatch)"))
    }

    @Test fun screenTransitionsDoNotRequestOrStopStreamsAndFavoritesRemainInCategories() {
        val viewModel = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        for (method in listOf("showPanel", "openGuide", "returnToFullscreen")) {
            val body = viewModel.substringAfter("fun $method(").substringBefore("\n    }")
            assertFalse(body.contains("startLive("))
            assertFalse(body.contains("playback.stop("))
            assertFalse(body.contains("select("))
        }
        val library = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/LibraryViewModel.kt").readText()
        assertTrue(library.contains("add(GroupItem(GroupKey.Favorites, favorites))"))
        assertTrue(library.contains("GroupKey.Favorites -> channelDao.pagingFavorites(id)"))
        assertFalse(File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/HomeScreen.kt").exists())
        assertFalse(File("src/main/kotlin/dev/firetvlite/iptv/ui/navigation/AppNavigationRail.kt").exists())
    }

    @Test fun guideLabelAndQuickMenuCopyArePolishResourcesWithNoMainMenu() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string")
        val values = (0 until strings.length).map { strings.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        assertEquals("Oś czasu", values["nav_guide"])
        assertEquals("Ścieżka dźwiękowa", values["quick_menu_audio"])
        assertFalse(values.values.any { it.contains("Menu główne") })
        assertFalse(values.keys.any { it.startsWith("home_") || it == "nav_home" })
    }
}
