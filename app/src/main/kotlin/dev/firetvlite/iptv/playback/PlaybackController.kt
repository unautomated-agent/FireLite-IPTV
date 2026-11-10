package dev.firetvlite.iptv.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What to play. */
data class StreamRequest(
    val url: String,
    val userAgent: String,
)

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Connecting : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data object Paused : PlaybackStatus
    data object Ended : PlaybackStatus
    data class Failed(val code: String) : PlaybackStatus
}

/** Time information of the running stream, polled by the UI while the info bar is visible. */
data class PlaybackSnapshot(
    /** True if the user can jump back and forth (HLS with a time-shift window, or an archive stream). */
    val seekable: Boolean,
    /** How far behind the live edge playback is, or null if the stream is not live. */
    val liveOffsetMs: Long?,
    val positionMs: Long,
    val durationMs: Long?,
) {
    companion object {
        val EMPTY = PlaybackSnapshot(seekable = false, liveOffsetMs = null, positionMs = 0, durationMs = null)
    }
}

/**
 * The only place in the app that opens a stream. It owns exactly one [ExoPlayer] and therefore at most one
 * connection to the provider.
 *
 * How the rule "never two streams" is kept:
 * 1. [StreamGate] drops a request for the stream that is already active.
 * 2. A new stream is only opened after the old one was stopped **and** a short grace period has passed,
 *    so the old connection is closed on the server side first. Rapid requests replace each other during
 *    that period; only the last one ever connects.
 * 3. The stream is stopped whenever the app is paused (see [suspendStream]), so nothing runs in the background.
 *
 * Must be used from the main thread.
 */
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val gate = StreamGate()
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    private var exo: ExoPlayer? = null
    private var startJob: Job? = null
    private var retries = 0
    private var activeRequest: StreamRequest? = null
    private var suspendedRequest: StreamRequest? = null

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private val mutableStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val status: StateFlow<PlaybackStatus> = mutableStatus.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshStatus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshStatus()

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    /** Plays [request]. A request for the stream that is already active does nothing. */
    fun play(request: StreamRequest) {
        if (gate.onRequest(request.url) == StreamGate.Action.Ignore) return
        activeRequest = request
        retries = 0
        startJob?.cancel()
        httpFactory.setUserAgent(request.userAgent)

        val player = ensurePlayer()
        player.stop() // first close the old connection ...
        player.clearMediaItems()
        mutableStatus.value = PlaybackStatus.Connecting

        startJob = scope.launch {
            delay(CLOSE_GRACE_MS) // ... and give the server time to notice, only then open the new one
            player.setMediaItem(MediaItem.fromUri(request.url))
            player.playWhenReady = true
            player.prepare()
        }
    }

    /** Closes the stream for good. */
    fun stop() {
        startJob?.cancel()
        startJob = null
        activeRequest = null
        gate.onStopped()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutableStatus.value = PlaybackStatus.Idle
    }

    /** Called when the app goes to the background: closes the stream but remembers it. */
    fun suspendStream() {
        suspendedRequest = activeRequest ?: suspendedRequest
        stop()
    }

    /** Called when the app returns: reconnects the stream that was running before, if any. */
    fun resumeStream() {
        val request = suspendedRequest ?: return
        suspendedRequest = null
        play(request)
    }

    /** Frees the decoder. The player is created again when needed. */
    fun releasePlayer() {
        exo?.let {
            it.removeListener(listener)
            it.release()
        }
        exo = null
        mutablePlayer.value = null
    }

    fun togglePause() {
        val player = exo ?: return
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        exo?.pause()
    }

    fun resume() {
        exo?.play()
    }

    /** Jumps by [deltaMs] if the stream allows it. Returns false if it is not seekable. */
    fun seekBy(deltaMs: Long): Boolean {
        val player = exo ?: return false
        if (!player.isCurrentMediaItemSeekable) return false
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = player.duration
        if (deltaMs > 0 && duration != C.TIME_UNSET && target >= duration - LIVE_EDGE_SNAP_MS) {
            player.seekToDefaultPosition() // past the end of a live window means: back to live
        } else {
            player.seekTo(target)
        }
        return true
    }

    fun snapshot(): PlaybackSnapshot {
        val player = exo ?: return PlaybackSnapshot.EMPTY
        return PlaybackSnapshot(
            seekable = player.isCurrentMediaItemSeekable,
            liveOffsetMs = player.currentLiveOffset.takeIf { it != C.TIME_UNSET },
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
        )
    }

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.addListener(listener)
        exo = player
        mutablePlayer.value = player
        return player
    }

    private fun refreshStatus() {
        val player = exo ?: return
        if (activeRequest == null) return
        when (player.playbackState) {
            Player.STATE_BUFFERING -> mutableStatus.value = PlaybackStatus.Buffering
            Player.STATE_READY -> {
                retries = 0
                mutableStatus.value =
                    if (player.playWhenReady) PlaybackStatus.Playing else PlaybackStatus.Paused
            }
            Player.STATE_ENDED -> mutableStatus.value = PlaybackStatus.Ended
            else -> Unit // IDLE: keep Connecting or Failed
        }
    }

    private fun handleError(error: PlaybackException) {
        if (activeRequest == null) return
        if (retries < MAX_RETRIES) {
            retries++
            mutableStatus.value = PlaybackStatus.Connecting
            startJob?.cancel()
            startJob = scope.launch {
                delay(RETRY_DELAY_MS * retries)
                exo?.prepare() // the failed connection is closed already; open a fresh one
            }
        } else {
            mutableStatus.value = PlaybackStatus.Failed(error.errorCodeName)
            activeRequest = null
            gate.onStopped() // lets the user try the same channel again
            exo?.stop()
        }
    }

    private companion object {
        const val CLOSE_GRACE_MS = 400L
        const val RETRY_DELAY_MS = 1_500L
        const val MAX_RETRIES = 3
        const val LIVE_EDGE_SNAP_MS = 1_500L
    }
}
