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
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    var railHasFocus by remember { mutableStateOf(false) }
    var tabReturn by remember { mutableStateOf<BrowserTarget?>(null) }
    val railRequesters = remember { Destination.entries.associateWith { FocusRequester() } }

    // Back first returns focus to the rail; only a second press (with the rail focused) leaves the app.
    fun navigate(destination: Destination) {
        if (destination != Destination.Live) playerViewModel.close()
        tabReturn = null
        current = destination
    }

    BackHandler(enabled = !railHasFocus && !mode.fullFrame) {
        try {
            railRequesters.getValue(current).requestFocus()
        } catch (ignored: IllegalStateException) {
            // The rail is always composed; if focus cannot move, the system handles the next press.
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FireLiteTheme.colors.background),
    ) {
        AnimatedContent(
            targetState = current,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = RailCollapsedWidth),
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
                    active = current == Destination.Home && !mode.fullFrame, returnTarget = tabReturn,
                    updateState = updateState,
                    onInstallUpdate = updateViewModel::downloadAndInstall,
                    onLive = { navigate(Destination.Live) }, onSettings = { navigate(Destination.Settings) },
                )
                Destination.Live -> LiveTvScreen(
                    library = libraryViewModel,
                    player = playerViewModel,
                    active = current == Destination.Live,
                    onPreviewBounds = { previewBounds = it },
                    onOpenSettings = { navigate(Destination.Settings) },
                )
                Destination.Guide -> LiveTvScreen(
                    library = libraryViewModel, player = playerViewModel,
                    active = current == Destination.Guide, guideOnly = true, returnTarget = tabReturn,
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
                    current == Destination.Favorites && !mode.fullFrame, tabReturn, { navigate(Destination.Live) })
                Destination.Search -> SearchScreen(libraryViewModel, playerViewModel,
                    current == Destination.Search && !mode.fullFrame, tabReturn)
            }
        }

        AppNavigationRail(
            selected = current,
            focusRequesters = railRequesters,
            onSelect = ::navigate,
            onFocusWithinChanged = { railHasFocus = it },
        )
        PlayerLayer(viewModel = playerViewModel, library = libraryViewModel, previewBounds = previewBounds,
            onBackToBrowser = {
                if (mode.afterBack(current == Destination.Live) == PlayerMode.Preview) playerViewModel.backToPreview()
                else tabReturn = playerViewModel.closeToTab()
            })
    }
}
