package dev.firetvlite.iptv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun PlayerQuickMenu(viewModel: PlayerViewModel, onClose: () -> Unit, onMainMenu: (Boolean) -> Unit) {
    val player by viewModel.player.collectAsState()
    val favorite by viewModel.isFavorite.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf(false) }
    val tracks = remember(player, revision) { viewModel.audioTracks() }
    val focus = remember { FocusRequester() }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onTracksChanged(tracks: Tracks) { revision++ } }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(audio) { focus.requestFocusWhenReady() }
    BackHandler { if (audio) audio = false else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(590.dp).background(FireLiteTheme.colors.surface, FireLiteTheme.shapes.medium)
            .padding(28.dp).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FLText(if (audio) "Ścieżka dźwiękowa" else "Szybkie menu", style = FireLiteTheme.typography.headline)
            if (audio) {
                QuickMenuRow("‹ Wstecz", { audio = false }, Modifier.focusRequester(focus))
                if (tracks.isEmpty()) FLText("Brak dostępnych ścieżek dźwiękowych", style = FireLiteTheme.typography.body)
                LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tracks, key = { "${it.group}:${it.index}" }) { track ->
                        QuickMenuRow((if (track.selected) "✓  " else "    ") + track.title,
                            { viewModel.selectAudioTrack(track); revision++ })
                    }
                }
            } else {
                QuickMenuRow("Ścieżka dźwiękowa", { audio = true }, Modifier.focusRequester(focus))
                QuickMenuRow(if (favorite) "Usuń z ulubionych" else "Dodaj do ulubionych", viewModel::toggleFavorite)
                QuickMenuRow("Menu główne", { onMainMenu(false) })
                QuickMenuRow("Ustawienia", { onMainMenu(true) })
            }
        }
    }
}

@Composable
private fun QuickMenuRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.025f) {
        FLText(title, style = FireLiteTheme.typography.body, maxLines = 2, modifier = Modifier.padding(14.dp))
    }
}
