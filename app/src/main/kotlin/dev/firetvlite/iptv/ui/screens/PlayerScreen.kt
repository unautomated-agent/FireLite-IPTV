package dev.firetvlite.iptv.ui.screens

import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.BannerState
import dev.firetvlite.iptv.playback.PlaybackStatus
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.playback.ScreenAwakePolicy
import dev.firetvlite.iptv.playback.TvAction
import dev.firetvlite.iptv.playback.TvKey
import dev.firetvlite.iptv.playback.TvPlayerControls
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.remotePress
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerMessage
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerLayer(viewModel: PlayerViewModel, library: LibraryViewModel, previewBounds: Rect?, onMainMenu: (Boolean) -> Unit) {
    val mode by viewModel.mode.collectAsState()
    val player by viewModel.player.collectAsState()
    val status by viewModel.status.collectAsState()
    val startupDelayed by viewModel.startupDelayed.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    var playWhenReady by remember(player) { mutableStateOf(player?.playWhenReady == true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val keepAwake = ScreenAwakePolicy.keepAwake(mode, status, playWhenReady)
    val density = LocalDensity.current
    BackHandler(enabled = mode.fullFrame || mode == PlayerMode.Guide) {
        if (timeline != null) viewModel.closeTimeline()
        else if (mode == PlayerMode.Overlay) viewModel.closeOverlay()
        else if (mode == PlayerMode.Guide) viewModel.returnToFullscreen()
    }
    Box(Modifier.fillMaxSize()) {
        val video = when {
            mode.fullFrame -> Modifier.matchParentSize()
            mode == PlayerMode.Guide || mode == PlayerMode.MainMenu ->
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(228.dp, 128.dp)
            mode == PlayerMode.Preview && previewBounds != null -> Modifier
                .offset { IntOffset(previewBounds.left.toInt(), previewBounds.top.toInt()) }
                .size(with(density) { previewBounds.width.toDp() }, with(density) { previewBounds.height.toDp() })
            else -> Modifier.size(0.dp)
        }
        // Presentation changes resize this one view; they never replace the player or open a stream.
        AndroidView(factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
            }
        }, update = { view ->
            if (view.player !== player) view.player = player
            view.keepScreenOn = keepAwake
        }, onRelease = { view -> view.keepScreenOn = false; view.player = null }, modifier = video)
        if (mode == PlayerMode.Preview) Box(video) { StatusLayer(status) }
        if (mode == PlayerMode.Fullscreen && timeline == null) PlayerContent(viewModel, onMainMenu)
        if (mode == PlayerMode.Overlay) PlayerChannelOverlay(library, viewModel)
        timeline?.let { ChannelHistoryList(it, viewModel, library) }
        if (startupDelayed && mode != PlayerMode.Closed) FLText(stringResource(R.string.player_start_delayed),
            style = FireLiteTheme.typography.label, color = FireLiteTheme.colors.textPrimary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f)).padding(12.dp))
    }
}

@Composable
private fun PlayerContent(viewModel: PlayerViewModel, onMainMenu: (Boolean) -> Unit) {
    val status by viewModel.status.collectAsState()
    val channel by viewModel.channel.collectAsState()
    val frame by viewModel.frame.collectAsState()
    val guide by viewModel.programme.collectAsState()
    val message by viewModel.message.collectAsState()
    var controls by remember { mutableStateOf(TvPlayerControls()) }
    var quickMenu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    fun key(key: TvKey) {
        val result = controls.key(key, SystemClock.uptimeMillis(), panelOpen = quickMenu)
        controls = result.first
        when (result.second) {
            TvAction.None -> Unit
            TvAction.PreviousChannel -> viewModel.previousChannel()
            TvAction.Back10, TvAction.Forward10, TvAction.Back60, TvAction.Forward60 -> viewModel.seek(result.second.seekMs!!)
            TvAction.TogglePause -> viewModel.togglePause()
            TvAction.Channels -> viewModel.openOverlay()
            TvAction.Guide -> viewModel.openGuide()
            TvAction.History -> viewModel.openTimeline()
            TvAction.QuickMenu -> quickMenu = true
            TvAction.ScrollUp -> scope.launch { scroll.animateScrollBy(-100f) }
            TvAction.ScrollDown -> scope.launch { scroll.animateScrollBy(100f) }
        }
    }
    LaunchedEffect(quickMenu) { if (!quickMenu) focus.requestFocusWhenReady() }
    LaunchedEffect(controls.deadline) {
        if (controls.banner != BannerState.Hidden) {
            delay((controls.deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
            controls = controls.expire(SystemClock.uptimeMillis())
        }
    }
    LaunchedEffect(controls.banner, channel?.id) { scroll.scrollTo(0) }
    BackHandler(enabled = quickMenu || controls.banner != BannerState.Hidden) {
        if (quickMenu) quickMenu = false else key(TvKey.Back)
    }
    Box(Modifier.fillMaxSize().focusRequester(focus)
        .onPreviewKeyEvent { if (!quickMenu) controls = controls.touch(SystemClock.uptimeMillis()); false }
        .remotePress(setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter), { key(TvKey.Ok) }, { key(TvKey.LongOk) })
        .remotePress(setOf(Key.Menu), { key(TvKey.Menu) }, { key(TvKey.LongMenu) })
        .remotePress(setOf(Key.DirectionLeft), { key(TvKey.Left) }, { viewModel.jumpToProgrammeStart() })
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else {
                val mapped = when (event.key) {
                    Key.DirectionUp -> TvKey.Up
                    Key.DirectionDown -> TvKey.Down
                    Key.DirectionRight -> TvKey.Right
                    Key.MediaRewind -> TvKey.Rewind
                    Key.MediaFastForward -> TvKey.FastForward
                    Key.MediaPlayPause -> TvKey.PlayPause
                    else -> null
                }
                val first = event.nativeKeyEvent.repeatCount == 0
                if (mapped != null) {
                    if (first || controls.banner == BannerState.Expanded && mapped in setOf(TvKey.Up, TvKey.Down)) key(mapped)
                    true
                } else when (event.key) {
                    Key.ChannelUp -> { if (first) viewModel.zap(-1); true }
                    Key.ChannelDown -> { if (first) viewModel.zap(1); true }
                    Key.MediaPlay -> { if (first) viewModel.resume(); true }
                    Key.MediaPause -> { if (first) viewModel.pause(); true }
                    else -> false
                }
            }
        }.focusable()) {
        StatusLayer(status)
        message?.let { hint ->
            FLText(stringResource(if (hint == PlayerMessage.SeekUnsupported) R.string.player_seek_unsupported else R.string.player_catchup_unsupported),
                style = FireLiteTheme.typography.title, modifier = Modifier.align(Alignment.TopCenter)
                    .padding(24.dp).background(Color(0xCC000000)).padding(16.dp))
        }
        AnimatedVisibility(controls.banner != BannerState.Hidden, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically { it / 4 },
            exit = fadeOut(tween(180)) + slideOutVertically { it / 4 }) {
            PlayerInfoBanner(channel, frame, guide, controls.banner == BannerState.Expanded, scroll, viewModel.isArchive())
        }
    }
    if (quickMenu) PlayerQuickMenu(viewModel, onClose = { quickMenu = false }, onMainMenu = onMainMenu)
}

@Composable
private fun StatusLayer(status: PlaybackStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (status) {
            PlaybackStatus.Connecting, PlaybackStatus.Buffering -> Spinner()
            is PlaybackStatus.Failed -> FLText(stringResource(R.string.player_error, status.code), style = FireLiteTheme.typography.headline)
            PlaybackStatus.Ended -> FLText(stringResource(R.string.player_ended), style = FireLiteTheme.typography.headline)
            else -> Unit
        }
    }
}

@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerAngle")
    val color = FireLiteTheme.colors.accent
    Canvas(Modifier.size(44.dp)) {
        val stroke = 5.dp.toPx()
        drawArc(color, angle, 250f, false, topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 83_000 ms becomes "1:23"; one hour or more becomes "1:01:23". */
internal fun formatClock(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
