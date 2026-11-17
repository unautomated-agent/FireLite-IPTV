package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.ui.navigation.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class StreamNavigationTest {
    @Test fun selectionRetainsPreviewUsageOnlyForTheSelectedChannel() {
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val select = model.substringAfter("private fun select(").substringBefore("private fun setMode(")
        assertTrue(select.contains("setMode(mode, retainPreviewUsage = sameChannel)"))
        val mode = model.substringAfter("private fun setMode(").substringBefore("fun cancelStartupResume(")
        assertTrue(mode.contains("retainUsage = retainPreviewUsage"))
    }

    @Test fun reminderExclusionUsesRequestedLiveChannelBeforeFramesOrPlaybackAreReady() {
        val screen = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerScreen.kt").readText()
            .substringAfter("fun PlayerLayer(").substringBefore("private fun PlayerContent(")
        assertTrue(screen.contains("val liveChannelId by viewModel.liveChannelId.collectAsState()"))
        assertTrue(screen.contains("watchingLiveChannelId = liveChannelId"))
        assertFalse(screen.contains("frame.nowMs"))
        assertFalse(screen.contains("viewModel.isArchive()"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val select = model.substringAfter("private fun select(").substringBefore("private fun setMode(")
        assertTrue(select.contains("show(first, archive = archiveTime != null)"))
        assertTrue(select.indexOf("show(first,") < select.indexOf("startLive(first)"))
        val zap = model.substringAfter("fun zap(").substringBefore("fun close()")
        assertTrue(zap.contains("show(next, archive = false)"))
        assertTrue(zap.indexOf("show(next,") < zap.indexOf("delay(ZAP_DEBOUNCE_MS)"))
        val show = model.substringAfter("private fun show(").substringBefore("private fun startLive(")
        assertTrue(show.contains("archive: Boolean = archiveStartMs != null"))
        assertTrue(show.contains("mutableLiveChannelId.value = channel.id.takeUnless { archive }"))
        assertTrue(show.indexOf("mutableLiveChannelId.value") < show.indexOf("mutableChannel.value"))
        val startLive = model.substringAfter("private fun startLive(").substringBefore("private fun markWatched(")
        assertTrue(startLive.indexOf("mutableLiveChannelId.value = channel.id") < startLive.indexOf("playback.play("))
        val archive = model.substringAfter("private fun applyCatchup(").substringBefore("private fun show(")
        assertTrue(archive.contains("mutableLiveChannelId.value = null"))
        val close = model.substringAfter("fun close()").substringBefore("override fun onCleared()")
        assertTrue(close.contains("mutableLiveChannelId.value = null"))
    }

    @Test fun restoredChannelOverlayClosesOnFirstBackBeforeComposeCanClearRowFocus() {
        val restored = PlayerMode.Overlay
        assertEquals(OverlayBackAction.Consume, overlayBackAction(restored, false, false))
        assertEquals(OverlayBackAction.Consume, overlayBackAction(restored, false, false))
        assertEquals(OverlayBackAction.Consume, overlayBackAction(restored, true, true))
        assertEquals(OverlayBackAction.Close, overlayBackAction(restored, true, false))
        val closed = restored.afterBack()
        assertEquals(PlayerMode.Fullscreen, closed)
        for (mode in PlayerMode.entries.filter { it != restored }) {
            assertEquals(OverlayBackAction.Pass, overlayBackAction(mode, false, false))
            assertEquals(OverlayBackAction.Pass, overlayBackAction(mode, true, false))
        }
        val activity = File("src/main/kotlin/dev/firetvlite/iptv/MainActivity.kt").readText()
        val dispatch = activity.substringAfter("override fun dispatchKeyEvent(").substringBefore("override fun onCreate(")
        assertTrue(dispatch.indexOf("overlayBackAction(") < dispatch.indexOf("super.dispatchKeyEvent(event)"))
        assertTrue(dispatch.contains("OverlayBackAction.Consume -> return true"))
        assertTrue(dispatch.contains("OverlayBackAction.Close -> { playerViewModel.closeOverlay(); return true }"))
    }

    @Test fun guideBackBypassesFocusExitAndActsOnceOnReleaseWithOrWithoutPlayback() {
        for (hasChannel in listOf(false, true)) {
            assertEquals(GuideBackAction.Consume, guideBackAction(PlayerMode.Guide, hasChannel, false, false))
            assertEquals(GuideBackAction.Consume, guideBackAction(PlayerMode.Guide, hasChannel, false, false))
            assertEquals(GuideBackAction.Consume, guideBackAction(PlayerMode.Guide, hasChannel, true, true))
            assertEquals(if (hasChannel) GuideBackAction.ReturnToPlayback else GuideBackAction.Exit,
                guideBackAction(PlayerMode.Guide, hasChannel, true, false))
        }
        for (mode in PlayerMode.entries.filter { it != PlayerMode.Guide }) {
            assertEquals(GuideBackAction.Pass, guideBackAction(mode, true, false, false))
            assertEquals(GuideBackAction.Pass, guideBackAction(mode, false, true, false))
        }
        val activity = File("src/main/kotlin/dev/firetvlite/iptv/MainActivity.kt").readText()
        val dispatch = activity.substringAfter("override fun dispatchKeyEvent(").substringBefore("override fun onCreate(")
        assertTrue(dispatch.indexOf("guideBackAction(") < dispatch.indexOf("super.dispatchKeyEvent(event)"))
        assertTrue(dispatch.contains("if (event.keyCode == KeyEvent.KEYCODE_BACK && !playerViewModel.searchVisible.value)"))
        assertTrue(dispatch.contains("GuideBackAction.ReturnToPlayback -> { playerViewModel.returnToFullscreen(); return true }"))
        assertTrue(dispatch.contains("GuideBackAction.Exit -> { finish(); return true }"))
    }

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
        val actions = listOf(QuickMenuAction.Films, QuickMenuAction.Settings, QuickMenuAction.Favorite, QuickMenuAction.Audio,
            QuickMenuAction.Search)
        assertEquals(actions, quickMenuItems(false).map { it.first })
        assertEquals(actions, quickMenuItems(true).map { it.first })
        assertEquals(listOf(R.string.nav_films, R.string.nav_settings, R.string.quick_menu_add_favorite,
            R.string.quick_menu_audio, R.string.search), quickMenuItems(false).map { it.second })
        assertEquals(R.string.quick_menu_remove_favorite, quickMenuItems(true)[2].second)
    }

    @Test fun searchKeyAndQuickMenuOpenTheSameLocalOverlayAndSelectionUsesExistingPlayback() {
        val activity = File("src/main/kotlin/dev/firetvlite/iptv/MainActivity.kt").readText()
        assertTrue(activity.contains("event.keyCode == KeyEvent.KEYCODE_SEARCH"))
        assertTrue(activity.contains("event.repeatCount == 0) playerViewModel.openSearch()"))
        val menu = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerQuickMenu.kt").readText()
        assertTrue(menu.contains("QuickMenuAction.Search -> { onClose(); viewModel.openSearch() }"))
        val vm = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val opening = vm.substringAfter("fun openSearch()").substringBefore("fun closeSearch()")
        assertFalse(opening.contains("playback.play("))
        val selection = vm.substringAfter("fun playSearchResult(").substringBefore("fun dismissTimeline()")
        assertTrue(selection.contains("archiveTime = archiveStart, expectedChannel = channel"))
        val resolving = vm.substringAfter("private fun select(").substringBefore("private fun setMode(")
        assertTrue(resolving.contains("container.database.withTransaction"))
        assertTrue(resolving.contains("ChannelSelection.resolve(expectedChannel, zap)"))
        assertTrue(resolving.contains("applyCatchup(first, archiveTime, restartIfActive = true)"))
        assertTrue(resolving.contains("startLive(first)"))
    }

    @Test fun quickMenuFocusAndAudioBackAreIndependentOfTrackAndFavoriteUpdates() {
        val menu = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerQuickMenu.kt").readText()
        assertTrue(menu.contains("LaunchedEffect(audio, active)"))
        assertTrue(menu.contains("QuickMenuAction.Films -> Modifier.focusRequester(firstFocus)"))
        assertTrue(menu.contains("if (audio) { returningFromAudio = true; audio = false } else onClose()"))
        assertTrue(menu.contains("if (audio) submenuFocus else if (returningFromAudio) audioFocus else firstFocus"))
        assertTrue(menu.contains("if (active && event.key == Key.Back)"))
        assertTrue(menu.contains("if (tracks.isEmpty())"))
        assertFalse(menu.contains("openGuide("))
        assertFalse(menu.contains("startLive("))
        assertFalse(menu.contains("viewModel.open("))
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
        assertTrue(settings.contains("SettingsSection.Sources -> SourceSection("))
        assertTrue(settings.contains("if (canWatch) FocusSurface(onClick = onWatch)"))
        assertTrue(settings.contains("stringResource(R.string.settings_watch_live)"))
    }

    @Test fun screenTransitionsDoNotRequestOrStopStreamsAndFavoritesRemainInCategories() {
        val viewModel = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        for (method in listOf("showPanel", "openGuide")) {
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

    @Test fun fullscreenReturnRestartsAnAbsentStreamUnlessReconnectWasCancelled() {
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val body = model.substringAfter("fun returnToFullscreen()").substringBefore("fun openGuide(")
        assertTrue(body.contains("val channel = activeChannel"))
        assertTrue(body.contains("if (channel != null && !playback.hasActiveStream && !reconnectCancelled) {"))
        assertTrue(body.contains("if (start == null) startLive(channel) else applyCatchup(channel, start)"))
        assertTrue(body.indexOf("!playback.hasActiveStream") < body.indexOf("startLive(channel)"))
        assertTrue(body.indexOf("startLive(channel)") < body.indexOf("setMode("))
        assertFalse(body.contains("playback.stop("))
        assertFalse(body.contains("prepare("))
    }

    @Test fun remoteCancellationRunsBeforeBackRoutingAndNavigationAlsoCancels() {
        val activity = File("src/main/kotlin/dev/firetvlite/iptv/MainActivity.kt").readText()
        val dispatch = activity.substringAfter("override fun dispatchKeyEvent(").substringBefore("override fun dispatchTouchEvent(")
        assertTrue(dispatch.indexOf("playerViewModel.cancelAutoReconnect()") < dispatch.indexOf("overlayBackAction("))
        assertTrue(dispatch.contains("event.action == KeyEvent.ACTION_DOWN"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val cancel = model.substringAfter("fun cancelAutoReconnect()").substringBefore("private val mutableBrowserTarget")
        assertTrue(cancel.contains("if (playback.cancelAutoReconnect()) reconnectCancelled = true"))
        val mode = model.substringAfter("private fun setMode(").substringBefore("fun cancelStartupResume(")
        assertTrue(mode.contains("cancelAutoReconnect()"))
    }

    @Test fun openingSearchCancelsReconnectBeforeSearchKeyRoutingWithoutStartingPlayback() {
        val activity = File("src/main/kotlin/dev/firetvlite/iptv/MainActivity.kt").readText()
        val dispatch = activity.substringAfter("override fun dispatchKeyEvent(").substringBefore("override fun dispatchTouchEvent(")
        assertTrue(dispatch.indexOf("playerViewModel.cancelAutoReconnect()") < dispatch.indexOf("KeyEvent.KEYCODE_SEARCH"))
        assertTrue(dispatch.contains("KeyEvent.KEYCODE_BACK && !playerViewModel.searchVisible.value"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val search = model.substringAfter("fun openSearch() {").substringBefore("fun closeSearch()")
        assertTrue(search.indexOf("cancelAutoReconnect()") < search.indexOf("mutableSearchVisible.value = true"))
        assertFalse(search.contains("startLive("))
        assertFalse(search.contains("playback.play("))
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
