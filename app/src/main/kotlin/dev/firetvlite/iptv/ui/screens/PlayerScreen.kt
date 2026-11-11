package dev.firetvlite.iptv.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.playback.PlaybackSnapshot
import dev.firetvlite.iptv.playback.PlaybackStatus
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.NavIcon
import dev.firetvlite.iptv.ui.components.NavIconKind
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.PlayerMessage
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.ui.viewmodel.ZapPosition
import kotlinx.coroutines.delay

private const val OVERLAY_VISIBLE_MS = 5_000L
private const val SNAPSHOT_INTERVAL_MS = 500L
private const val SEEK_STEP_MS = 10_000L

/**
 * Full-screen player. It is a dialog window, so the screen underneath keeps its focus and scroll position
 * and the user lands exactly where they were when the player closes.
 *
 * Remote: Up/Down change the channel, Left/Right jump 10 s, rewind/fast-forward jump 30 s (or enter the archive),
 * OK shows the info bar, Menu toggles the favourite, Back closes the player.
 */
@Composable
fun PlayerDialog(viewModel: PlayerViewModel) {
    val open by viewModel.isOpen.collectAsState()
    if (!open) return

    Dialog(
        onDismissRequest = viewModel::close,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        PlayerContent(viewModel)
    }
}

@Composable
private fun PlayerContent(viewModel: PlayerViewModel) {
    val player by viewModel.player.collectAsState()
    val status by viewModel.status.collectAsState()
    val channel by viewModel.channel.collectAsState()
    val position by viewModel.position.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val catchupOffset by viewModel.catchupOffsetMinutes.collectAsState()
    val message by viewModel.message.collectAsState()

    var overlayVisible by remember { mutableStateOf(true) }
    var overlayToken by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(PlaybackSnapshot.EMPTY) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { rootFocus.requestFocusWhenReady() }

    // Every key press and every channel change shows the info bar for a few seconds.
    LaunchedEffect(overlayToken, channel) {
        overlayVisible = true
        delay(OVERLAY_VISIBLE_MS)
        overlayVisible = false
    }
    LaunchedEffect(overlayVisible) {
        while (overlayVisible) {
            snapshot = viewModel.snapshot()
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val firstPress = event.nativeKeyEvent.repeatCount == 0
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        if (firstPress) viewModel.zap(-1)
                        overlayToken++
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        if (firstPress) viewModel.zap(1)
                        overlayToken++
                        true
                    }
                    Key.DirectionLeft -> {
                        viewModel.seek(-SEEK_STEP_MS)
                        overlayToken++
                        true
                    }
                    Key.DirectionRight -> {
                        viewModel.seek(SEEK_STEP_MS)
                        overlayToken++
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        overlayToken++
                        true
                    }
                    Key.Menu -> {
                        if (firstPress) viewModel.toggleFavorite()
                        overlayToken++
                        true
                    }
                    Key.MediaPlayPause -> {
                        viewModel.togglePause()
                        overlayToken++
                        true
                    }
                    Key.MediaPlay -> {
                        viewModel.resume()
                        true
                    }
                    Key.MediaPause -> {
                        viewModel.pause()
                        true
                    }
                    Key.MediaRewind -> {
                        if (firstPress) viewModel.jump(forward = false)
                        overlayToken++
                        true
                    }
                    Key.MediaFastForward -> {
                        if (firstPress) viewModel.jump(forward = true)
                        overlayToken++
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        StatusLayer(status = status)

        message?.let { hint ->
            FLText(
                text = stringResource(
                    when (hint) {
                        PlayerMessage.SeekUnsupported -> R.string.player_seek_unsupported
                        PlayerMessage.CatchupUnsupported -> R.string.player_catchup_unsupported
                    },
                ),
                style = FireLiteTheme.typography.title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(FireLiteTheme.shapes.medium)
                    .background(Color(0xCC000000))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(260)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBar(
                channel = channel,
                position = position,
                isFavorite = isFavorite,
                snapshot = snapshot,
                catchupOffset = catchupOffset,
            )
        }
    }
}

@Composable
private fun StatusLayer(status: PlaybackStatus) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            PlaybackStatus.Connecting, PlaybackStatus.Buffering -> Spinner()
            is PlaybackStatus.Failed -> StatusMessage(stringResource(R.string.player_error, status.code))
            PlaybackStatus.Ended -> StatusMessage(stringResource(R.string.player_ended))
            else -> Unit
        }
    }
}

@Composable
private fun StatusMessage(text: String) {
    FLText(
        text = text,
        style = FireLiteTheme.typography.headline,
        modifier = Modifier
            .clip(FireLiteTheme.shapes.large)
            .background(Color(0xCC000000))
            .padding(horizontal = 28.dp, vertical = 20.dp),
    )
}

@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "spinnerAngle",
    )
    val color = FireLiteTheme.colors.accent
    Canvas(modifier = Modifier.size(44.dp)) {
        val strokeWidth = 5.dp.toPx()
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun InfoBar(
    channel: ChannelEntity?,
    position: ZapPosition,
    isFavorite: Boolean,
    snapshot: PlaybackSnapshot,
    catchupOffset: Int,
) {
    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChannelLogo(
                name = channel?.name.orEmpty(),
                logoUrl = channel?.logoUrl,
                size = 56.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FLText(
                        text = stringResource(R.string.player_position_of, position.number, position.count),
                        style = typography.label,
                        color = colors.accent,
                    )
                    if (isFavorite) {
                        NavIcon(
                            kind = NavIconKind.Favorites,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                FLText(
                    text = channel?.name.orEmpty(),
                    style = typography.display,
                    maxLines = 1,
                )
                FLText(
                    text = channel?.groupName.orEmpty(),
                    style = typography.body,
                    maxLines = 1,
                )
            }
            FLText(
                text = timeLabel(snapshot, catchupOffset),
                style = typography.title,
                color = if (catchupOffset > 0) Color(0xFFFFB74D) else colors.textPrimary,
            )
        }

        val duration = snapshot.durationMs
        if (snapshot.seekable && duration != null && duration > 0) {
            ProgressBar(
                progress = snapshot.positionMs.toFloat() / duration.toFloat(),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        FLText(
            text = stringResource(R.string.player_hint),
            style = typography.label,
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun timeLabel(snapshot: PlaybackSnapshot, catchupOffset: Int): String {
    if (catchupOffset > 0) return stringResource(R.string.player_archive, catchupOffset)
    val behind = snapshot.liveOffsetMs
    if (behind != null) {
        return if (behind < LIVE_TOLERANCE_MS) {
            stringResource(R.string.player_live)
        } else {
            stringResource(R.string.player_behind, formatClock(behind))
        }
    }
    val duration = snapshot.durationMs
    if (snapshot.seekable && duration != null) {
        return stringResource(R.string.player_position, formatClock(snapshot.positionMs), formatClock(duration))
    }
    return stringResource(R.string.player_live)
}

private const val LIVE_TOLERANCE_MS = 4_000L

/** 83_000 ms becomes "1:23"; one hour or more becomes "1:01:23". */
internal fun formatClock(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
