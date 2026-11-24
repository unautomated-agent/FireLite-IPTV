package dev.firetvlite.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.programmeTimes
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.LocalFilmRatings
import dev.firetvlite.iptv.ui.components.LocalChannelLogosEnabled
import dev.firetvlite.iptv.ui.components.WarsawClockBox
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.components.remoteHomeKey
import dev.firetvlite.iptv.ui.navigation.StreamPanel
import dev.firetvlite.iptv.ui.navigation.canChooseChannel
import dev.firetvlite.iptv.ui.navigation.visiblePanel
import dev.firetvlite.iptv.ui.navigation.screenBackReturnsToPlayback
import dev.firetvlite.iptv.ui.screens.FilmRankingsScreen
import dev.firetvlite.iptv.ui.screens.GuideDetails
import dev.firetvlite.iptv.ui.screens.LiveTvScreen
import dev.firetvlite.iptv.ui.screens.PlayerLayer
import dev.firetvlite.iptv.ui.screens.SettingsScreen
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.theme.ReferenceDensity
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.ui.screens.SearchDialog
import dev.firetvlite.iptv.update.UpdateViewModel
import kotlinx.coroutines.delay

@Composable
fun FireLiteApp(updateViewModel: UpdateViewModel, libraryViewModel: LibraryViewModel, playerViewModel: PlayerViewModel) {
    ReferenceDensity {
        FireLiteTheme {
            val startupReady by libraryViewModel.startupReady.collectAsState()
            val ratings = if (startupReady) libraryViewModel.films.ratings.collectAsState().value else emptyMap()
            CompositionLocalProvider(LocalFilmRatings provides ratings, LocalChannelLogosEnabled provides startupReady) {
                AppShell(updateViewModel, libraryViewModel, playerViewModel)
            }
        }
    }
}

/** Width kept free on the right of a panel for the small video in its top corner. */
private const val PANEL_VIDEO_INSET_DP = 216

@Composable
private fun AppShell(updateViewModel: UpdateViewModel, libraryViewModel: LibraryViewModel, playerViewModel: PlayerViewModel) {
    val startup = (LocalContext.current.applicationContext as FireLiteApplication).container.startup
    val firstDraw = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val startupReady by libraryViewModel.startupReady.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val automaticUpdate by updateViewModel.automaticUpdate.collectAsState()
    var dismissedUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    var openUpdates by rememberSaveable { mutableStateOf(false) }
    val source by libraryViewModel.source.collectAsState()
    val sources by libraryViewModel.sources.collectAsState()
    val channelCount by libraryViewModel.channelCount.collectAsState()
    val importState by libraryViewModel.importState.collectAsState()
    val epgStates by libraryViewModel.epgStates.collectAsState()
    val mode by playerViewModel.mode.collectAsState()
    val searchVisible by playerViewModel.searchVisible.collectAsState()
    val channel by playerViewModel.channel.collectAsState()
    val browserTarget by playerViewModel.browserTarget.collectAsState()
    var current by rememberSaveable { mutableStateOf(StreamPanel.Settings) }
    val settingsState = rememberSaveableStateHolder()

    fun navigate(panel: StreamPanel) {
        playerViewModel.cancelStartupResume()
        current = panel
        playerViewModel.showPanel()
    }

    val settingsRequested by playerViewModel.settingsRequested.collectAsState()
    // Not held back by startup: a first stream the provider keeps waiting must not block the way to Settings.
    LaunchedEffect(settingsRequested) {
        if (settingsRequested) {
            playerViewModel.requestSettings(false)
            navigate(StreamPanel.Settings)
        }
    }

    BackHandler(enabled = mode == PlayerMode.Panel && screenBackReturnsToPlayback(mode, channel != null)) {
        playerViewModel.returnToFullscreen()
    }

    Box(Modifier.fillMaxSize().background(FireLiteTheme.colors.background).drawWithContent {
        drawContent()
        if (firstDraw.compareAndSet(false, true)) startup.mark(LaunchPhase.Compose)
    }) {
        if (!startupReady && (mode == PlayerMode.Closed || mode == PlayerMode.Panel)) {
            FLText(stringResource(R.string.startup_loading), style = FireLiteTheme.typography.title,
                modifier = Modifier.align(Alignment.Center))
        }
        if (startupReady) visiblePanel(mode, current)?.let { panel ->
            // Content starts beside the small video instead of below it; the end inset keeps it clear of that video.
            Box(Modifier.fillMaxSize().padding(top = 56.dp, end = if (mode == PlayerMode.Panel) PANEL_VIDEO_INSET_DP.dp else 0.dp)) {
                when (panel) {
                    StreamPanel.Films -> FilmRankingsScreen(libraryViewModel, playerViewModel, active = true,
                        onSettings = { navigate(StreamPanel.Settings) })
                    StreamPanel.Settings -> settingsState.SaveableStateProvider("settings") { SettingsScreen(
                        installed = updateViewModel.installed,
                        updateState = updateState,
                        onCheckForUpdate = updateViewModel::checkForUpdate,
                        onInstallUpdate = updateViewModel::downloadAndInstall,
                        onOpenInstallSettings = updateViewModel::openInstallSettings,
                        source = source, sources = sources, importState = importState, epgStates = epgStates,
                        onSaveSource = libraryViewModel::saveSource,
                        onActivateSource = libraryViewModel::activateSource,
                        onDeleteSource = libraryViewModel::deleteSource,
                        onRefreshSource = libraryViewModel::refreshSource,
                        diagnostics = libraryViewModel.diagnostics,
                        canWatch = canChooseChannel(source != null, channelCount),
                        onWatch = { playerViewModel.cancelStartupResume(); playerViewModel.openGuide() },
                        openUpdates = openUpdates,
                        onUpdatesOpened = { openUpdates = false },
                    ) }
                }
            }
        }
        if (mode == PlayerMode.Guide) Column(Modifier.fillMaxSize().background(FireLiteTheme.colors.background)) {
            var details by remember { mutableStateOf<GuideDetails?>(null) }
            GuideHeader(details)
            LiveTvScreen(libraryViewModel, playerViewModel, active = true, guideOnly = true,
                returnTarget = browserTarget, onPreviewBounds = {}, onOpenSettings = { navigate(StreamPanel.Settings) },
                onGuideDetails = { details = it })
        }
        PlayerLayer(viewModel = playerViewModel, library = libraryViewModel, previewBounds = null,
            onFilms = { navigate(StreamPanel.Films) }, onSettings = { navigate(StreamPanel.Settings) })
        if (startupReady) WarsawClockBox(Modifier.align(Alignment.TopStart).padding(16.dp))
        automaticUpdate?.takeIf { it.tag != dismissedUpdate }?.let { release ->
            var hintVisible by rememberSaveable(release.tag) { mutableStateOf(true) }
            LaunchedEffect(release.tag) {
                delay(8_000)
                hintVisible = false
            }
            if (mode.fullFrame) {
                if (hintVisible) FLText(stringResource(R.string.update_hint),
                    style = FireLiteTheme.typography.title,
                    modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
                        .background(FireLiteTheme.colors.surfaceHigh).padding(16.dp))
            } else {
                Popup(alignment = Alignment.TopEnd,
                    onDismissRequest = { dismissedUpdate = release.tag },
                    properties = PopupProperties(focusable = true, dismissOnBackPress = true)) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
                    Column(Modifier.remoteHomeKey().padding(24.dp).background(FireLiteTheme.colors.surfaceHigh).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FLText(stringResource(R.string.update_new_build, release.buildNumber), style = FireLiteTheme.typography.title)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(stringResource(R.string.update_view), {
                                dismissedUpdate = release.tag
                                updateViewModel.showAutomaticUpdate(release)
                                openUpdates = true
                                navigate(StreamPanel.Settings)
                            }, Modifier.focusRequester(focus))
                            SecondaryButton(stringResource(R.string.update_later), { dismissedUpdate = release.tag })
                        }
                    }
                }
            }
        }
        if (searchVisible) SearchDialog(source?.id, playerViewModel)
    }
}

/** Fills the band beside the small video with the programme under the cursor; its height never changes. */
@Composable
private fun GuideHeader(details: GuideDetails?) {
    val type = FireLiteTheme.typography
    Column(Modifier.fillMaxWidth().height(148.dp).padding(start = 32.dp, top = 52.dp, end = 264.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (details == null) {
            FLText(stringResource(R.string.nav_guide), style = type.headline, modifier = Modifier.padding(top = 8.dp))
        } else {
            val programme = details.programme
            FLText("${details.channelName} · ${programmeTimes(programme)}", style = type.label,
                color = FireLiteTheme.colors.accent, maxLines = 1)
            FLText(programme.title, style = type.headline, maxLines = 1)
            programme.description?.takeIf { it.isNotBlank() }?.let {
                FLText(it, style = type.label, color = FireLiteTheme.colors.textSecondary, maxLines = 2)
            }
        }
    }
}
