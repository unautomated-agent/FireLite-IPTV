package dev.firetvlite.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.navigation.AppNavigationRail
import dev.firetvlite.iptv.ui.navigation.Destination
import dev.firetvlite.iptv.ui.navigation.RailCollapsedWidth
import dev.firetvlite.iptv.ui.screens.SearchScreen
import dev.firetvlite.iptv.ui.screens.HomeScreen
import dev.firetvlite.iptv.ui.screens.FavoritesScreen
import dev.firetvlite.iptv.ui.screens.LiveTvScreen
import dev.firetvlite.iptv.ui.screens.PlayerLayer
import dev.firetvlite.iptv.ui.screens.SettingsScreen
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.theme.ReferenceDensity
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.update.UpdateViewModel

/** Root of the UI: normalised density, theme and the navigation shell. */
@Composable
fun FireLiteApp(
    updateViewModel: UpdateViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
) {
    ReferenceDensity {
        FireLiteTheme {
            AppShell(updateViewModel, libraryViewModel, playerViewModel)
        }
    }
}

@Composable
private fun AppShell(
    updateViewModel: UpdateViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
) {
    val updateState by updateViewModel.state.collectAsState()
    val source by libraryViewModel.source.collectAsState()
    val sources by libraryViewModel.sources.collectAsState()
    val importState by libraryViewModel.importState.collectAsState()
    val epgStates by libraryViewModel.epgStates.collectAsState()
    val mode by playerViewModel.mode.collectAsState()
    val browserTarget by playerViewModel.browserTarget.collectAsState()
    val mainMenuVisible = mode == PlayerMode.Closed || mode == PlayerMode.MainMenu || mode == PlayerMode.Preview
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    var railHasFocus by remember { mutableStateOf(false) }
    var tabReturn by remember { mutableStateOf<BrowserTarget?>(null) }
    val railRequesters = remember { Destination.entries.associateWith { FocusRequester() } }

    fun navigate(destination: Destination) {
        playerViewModel.cancelStartupResume()
        playerViewModel.cancelPreview()
        if (mode.onNavigate(destination == Destination.Live) == PlayerMode.Closed && mode == PlayerMode.Preview)
            playerViewModel.close()
        tabReturn = null
        current = destination
    }

    BackHandler(enabled = mainMenuVisible && (!railHasFocus || mode != PlayerMode.Closed)) {
        if (railHasFocus) playerViewModel.returnToFullscreen() else {
            try {
                railRequesters.getValue(current).requestFocus()
            } catch (ignored: IllegalStateException) {
                // A destination transition may still be composing the selected rail item.
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FireLiteTheme.colors.background),
    ) {
        if (mainMenuVisible) AnimatedContent(
            targetState = current,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = RailCollapsedWidth, top = if (mode == PlayerMode.MainMenu) 148.dp else 0.dp),
            transitionSpec = {
                (fadeIn(tween(durationMillis = 260, delayMillis = 60)) +
                    slideInVertically(tween(durationMillis = 300)) { fullHeight -> fullHeight / 28 }) togetherWith
                    fadeOut(tween(durationMillis = 120))
            },
            label = "screen",
        ) { destination ->
            when (destination) {
                Destination.Home -> HomeScreen(
                    library = libraryViewModel, player = playerViewModel,
                    active = current == Destination.Home && mainMenuVisible, returnTarget = tabReturn,
                    updateState = updateState,
                    onInstallUpdate = updateViewModel::downloadAndInstall,
                    onLive = { navigate(Destination.Live) }, onSettings = { navigate(Destination.Settings) },
                )
                Destination.Live -> LiveTvScreen(
                    library = libraryViewModel,
                    player = playerViewModel,
                    active = current == Destination.Live && mainMenuVisible,
                    onPreviewBounds = { previewBounds = it },
                    onOpenSettings = { navigate(Destination.Settings) },
                )
                Destination.Guide -> LiveTvScreen(
                    library = libraryViewModel, player = playerViewModel,
                    active = current == Destination.Guide && mainMenuVisible, guideOnly = true, returnTarget = tabReturn,
                    onPreviewBounds = {}, onOpenSettings = { navigate(Destination.Settings) },
                )
                Destination.Settings -> SettingsScreen(
                    installed = updateViewModel.installed,
                    updateState = updateState,
                    onCheckForUpdate = updateViewModel::checkForUpdate,
                    onInstallUpdate = updateViewModel::downloadAndInstall,
                    onOpenInstallSettings = updateViewModel::openInstallSettings,
                    source = source,
                    sources = sources,
                    importState = importState,
                    epgStates = epgStates,
                    onSaveSource = libraryViewModel::saveSource,
                    onActivateSource = libraryViewModel::activateSource,
                    onDeleteSource = libraryViewModel::deleteSource,
                    onRefreshSource = libraryViewModel::refreshSource,
                    diagnostics = libraryViewModel.diagnostics,
                )
                Destination.Favorites -> FavoritesScreen(libraryViewModel, playerViewModel,
                    current == Destination.Favorites && mainMenuVisible, tabReturn, { navigate(Destination.Live) })
                Destination.Search -> SearchScreen(libraryViewModel, playerViewModel,
                    current == Destination.Search && mainMenuVisible, tabReturn)
            }
        }

        if (mode == PlayerMode.Guide) Column(Modifier.fillMaxSize().background(FireLiteTheme.colors.background)) {
            FLText("Program TV", style = FireLiteTheme.typography.headline,
                modifier = Modifier.padding(start = 32.dp, top = 60.dp, bottom = 60.dp))
            LiveTvScreen(libraryViewModel, playerViewModel, active = true, guideOnly = true,
                returnTarget = browserTarget, onPreviewBounds = {}, onOpenSettings = { navigate(Destination.Settings) })
        }
        if (mainMenuVisible) AppNavigationRail(
            selected = current,
            focusRequesters = railRequesters,
            onSelect = ::navigate,
            onFocusWithinChanged = { railHasFocus = it },
        )
        PlayerLayer(viewModel = playerViewModel, library = libraryViewModel, previewBounds = previewBounds,
            onMainMenu = { settings ->
                playerViewModel.showMainMenu()
                navigate(if (settings) Destination.Settings else Destination.Home)
            })
    }
}
