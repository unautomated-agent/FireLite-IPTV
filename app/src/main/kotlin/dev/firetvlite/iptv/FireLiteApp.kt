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
import dev.firetvlite.iptv.ui.navigation.AppNavigationRail
import dev.firetvlite.iptv.ui.navigation.Destination
import dev.firetvlite.iptv.ui.navigation.RailCollapsedWidth
import dev.firetvlite.iptv.ui.screens.ComingSoonScreen
import dev.firetvlite.iptv.ui.screens.HomeScreen
import dev.firetvlite.iptv.ui.screens.LiveTvScreen
import dev.firetvlite.iptv.ui.screens.PlayerDialog
import dev.firetvlite.iptv.ui.screens.SettingsScreen
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.theme.ReferenceDensity
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
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
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    var railHasFocus by remember { mutableStateOf(false) }
    val railRequesters = remember { Destination.entries.associateWith { FocusRequester() } }

    // Back first returns focus to the rail; only a second press (with the rail focused) leaves the app.
    BackHandler(enabled = !railHasFocus) {
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
                    updateState = updateState,
                    onInstallUpdate = updateViewModel::downloadAndInstall,
                )
                Destination.Live -> LiveTvScreen(
                    library = libraryViewModel,
                    player = playerViewModel,
                    onOpenSettings = { current = Destination.Settings },
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
                    onSaveSource = libraryViewModel::saveSource,
                    onActivateSource = libraryViewModel::activateSource,
                    onDeleteSource = libraryViewModel::deleteSource,
                    onRefreshSource = libraryViewModel::refreshSource,
                )
                else -> ComingSoonScreen(destination)
            }
        }

        AppNavigationRail(
            selected = current,
            focusRequesters = railRequesters,
            onSelect = { current = it },
            onFocusWithinChanged = { railHasFocus = it },
        )
    }

    // The player is a dialog window above everything else.
    PlayerDialog(viewModel = playerViewModel)
}
