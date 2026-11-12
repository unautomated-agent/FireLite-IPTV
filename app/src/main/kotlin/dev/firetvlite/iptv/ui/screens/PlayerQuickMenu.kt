package dev.firetvlite.iptv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.navigation.QuickMenuAction
import dev.firetvlite.iptv.ui.navigation.quickMenuItems
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun PlayerQuickMenu(viewModel: PlayerViewModel, active: Boolean, onClose: () -> Unit,
    onFilms: () -> Unit, onSettings: () -> Unit) {
    val player by viewModel.player.collectAsState()
    val channel by viewModel.channel.collectAsState()
    val programme by viewModel.programme.collectAsState()
    val favorite by viewModel.isFavorite.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf(false) }
    var returningFromAudio by remember { mutableStateOf(false) }
    val tracks = remember(player, revision) { viewModel.audioTracks() }
    val firstFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val submenuFocus = remember { FocusRequester() }
    val colors = FireLiteTheme.colors
    val shape = RoundedCornerShape(24.dp)
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onTracksChanged(tracks: Tracks) { revision++ } }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(audio, active) {
        if (active) (if (audio) submenuFocus else if (returningFromAudio) audioFocus else firstFocus).requestFocusWhenReady()
    }
    fun back() {
        if (audio) { returningFromAudio = true; audio = false } else onClose()
    }
    BackHandler(enabled = active) { back() }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(370.dp).shadow(20.dp, shape)
            .background(colors.surface.copy(alpha = 0.94f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape).padding(20.dp)
            .onPreviewKeyEvent { event ->
                if (active && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp && !event.nativeKeyEvent.isCanceled) back()
                    true
                } else false
            }
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelLogo(channel?.name.orEmpty(), channel?.logoUrl?.takeIf { it.isNotBlank() }, size = 42.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FLText(channel?.name.orEmpty(), style = FireLiteTheme.typography.title, maxLines = 1)
                    FLText(programme.current?.title ?: stringResource(R.string.quick_menu_no_programme),
                        style = FireLiteTheme.typography.body, color = colors.textSecondary, maxLines = 1)
                }
            }
            FLText(stringResource(if (audio) R.string.quick_menu_audio else R.string.quick_menu_title),
                style = FireLiteTheme.typography.label, color = colors.textSecondary,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp))
            if (audio) {
                QuickMenuRow(stringResource(R.string.quick_menu_back), R.drawable.menu_back, onClick = { back() },
                    active = active, modifier = Modifier.focusRequester(submenuFocus))
                if (tracks.isEmpty()) FLText(stringResource(R.string.quick_menu_no_audio),
                    style = FireLiteTheme.typography.body, modifier = Modifier.padding(14.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { "${it.group}:${it.index}" }) { track ->
                        QuickMenuRow(track.title, if (track.selected) R.drawable.menu_radio_checked else R.drawable.menu_radio,
                            subtitle = if (track.selected) stringResource(R.string.quick_menu_audio_selected) else null,
                            onClick = { viewModel.selectAudioTrack(track); revision++ }, active = active,
                            modifier = Modifier.semantics { selected = track.selected }, radio = true)
                    }
                }
            } else {
                quickMenuItems(favorite).forEach { (action, title) ->
                    val icon = when (action) {
                        QuickMenuAction.Films -> R.drawable.menu_movies
                        QuickMenuAction.Settings -> R.drawable.menu_settings
                        QuickMenuAction.Favorite -> R.drawable.menu_favorite
                        QuickMenuAction.Audio -> R.drawable.menu_audio
                    }
                    val subtitle = when (action) {
                        QuickMenuAction.Audio -> tracks.firstOrNull { it.selected }?.title
                            ?: stringResource(R.string.quick_menu_no_audio)
                        QuickMenuAction.Favorite -> stringResource(if (favorite) R.string.quick_menu_is_favorite else R.string.quick_menu_not_favorite)
                        else -> null
                    }
                    QuickMenuRow(stringResource(title), icon, subtitle, {
                        when (action) {
                            QuickMenuAction.Films -> onFilms()
                            QuickMenuAction.Settings -> onSettings()
                            QuickMenuAction.Favorite -> viewModel.toggleFavorite()
                            QuickMenuAction.Audio -> audio = true
                        }
                    }, active, when (action) {
                        QuickMenuAction.Films -> Modifier.focusRequester(firstFocus)
                        QuickMenuAction.Audio -> Modifier.focusRequester(audioFocus)
                        else -> Modifier
                    })
                }
            }
        }
    }
}

@Composable
private fun QuickMenuRow(title: String, icon: Int, subtitle: String? = null, onClick: () -> Unit,
    active: Boolean, modifier: Modifier = Modifier, radio: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val colors = FireLiteTheme.colors
    val background by animateColorAsState(if (focused) colors.textPrimary else Color.Transparent, tween(150), label = "menuPill")
    val foreground by animateColorAsState(if (focused) colors.background else colors.textPrimary, tween(150), label = "menuText")
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(150), label = "menuScale")
    Row(modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(32.dp)).background(background)
        .onFocusChanged { focused = it.isFocused }
        .clickable(enabled = active, role = if (radio) Role.RadioButton else Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(foreground.copy(alpha = 0.09f), CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(foreground), modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FLText(title, style = FireLiteTheme.typography.title, color = foreground, maxLines = 1)
            subtitle?.let {
                FLText(it, style = FireLiteTheme.typography.label,
                    color = if (focused) foreground.copy(alpha = 0.7f) else colors.textSecondary, maxLines = 1)
            }
        }
    }
}
