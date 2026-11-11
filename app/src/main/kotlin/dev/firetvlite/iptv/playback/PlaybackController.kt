package dev.firetvlite.iptv.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.io.Closeable
import java.util.Locale

/** What to play. */
data class StreamRequest(
    val url: String,
    val userAgent: String,
    val archive: Boolean = false,
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

/** Position and server window timestamps used to locate the watched frame in the guide. */
data class PlaybackSnapshot(
    /** True if the user can jump back and forth (HLS with a time-shift window, or an archive stream). */
    val seekable: Boolean,
    /** How far behind the live edge playback is, or null if the stream is not live. */
    val liveOffsetMs: Long?,
    val positionMs: Long,
    val durationMs: Long?,
    val windowStartMs: Long? = null,
) {
    companion object {
        val EMPTY = PlaybackSnapshot(seekable = false, liveOffsetMs = null, positionMs = 0, durationMs = null)
    }
}

/**
 * The only place in the app that opens a stream. It owns exactly one [ExoPlayer] and therefore at most one
 * streaming session with the provider. HTTP requests within that session may overlap.
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
class PlaybackController internal constructor(
    private val scope: CoroutineScope,
    private val createPlayer: (HttpDataSource.Factory) -> ExoPlayer,
    private val trace: (String) -> Unit,
    private val mediaItem: (String) -> MediaItem = { MediaItem.fromUri(it) },
    private val setStream: (ExoPlayer, MediaItem, HttpDataSource.Factory) -> Unit = { player, item, _ -> player.setMediaItem(item) },
    private val recordPlayback: (Boolean, String) -> Unit = { _, _ -> },
    private val progressiveItem: (String) -> MediaItem = {
        MediaItem.Builder().setUri(it).setMimeType(MimeTypes.VIDEO_MP2T).build()
    },
) {
    internal constructor(scope: CoroutineScope, createPlayer: (HttpDataSource.Factory) -> ExoPlayer) :
        this(scope, createPlayer, {})

    constructor(context: Context, scope: CoroutineScope) : this(scope, { httpFactory ->
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
    }, { Log.d("FireLiteStream", it) }, setStream = { player, item, factory ->
        player.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item))
    }, recordPlayback = diagnosticRecorder(context, scope))

    private val gate = StreamGate()
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    private var exo: ExoPlayer? = null
    private var startJob: Job? = null
    private var retries = 0
    private var activeSession = 0L
    private var fallbackUsed = false
    private var activeRequest: StreamRequest? = null
    private var suspendedRequest: StreamRequest? = null
    private var preview = false
    private var audioDecoder: String? = null
    private var successfulSignature: String? = null
    private val connections = AtomicInteger()
    private val connectionGate = HttpConnectionGate()
    private fun trackedFactory(session: Long) = object : HttpDataSource.Factory {
        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
            httpFactory.setDefaultRequestProperties(properties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            val delegate = httpFactory.createDataSource()
            return object : HttpDataSource by delegate {
                private var connected = false
                private var stream = 0
                private var connection: Closeable? = null

                override fun open(dataSpec: DataSpec): Long {
                    connection = connectionGate.acquire(session)
                    stream = dataSpec.uri.toString().hashCode()
                    connected = true
                    trace("HTTP_OPEN session=$session stream=$stream active=${connections.incrementAndGet()}")
                    return try {
                        delegate.open(dataSpec)
                    } catch (error: Exception) {
                        runCatching { close() }
                        throw error
                    }
                }

                override fun close() {
                    try {
                        delegate.close()
                    } finally {
                        if (connected) {
                            connected = false
                            trace("HTTP_CLOSE session=$session stream=$stream active=${connections.decrementAndGet()}")
                            connection?.close()
                            connection = null
                        }
                    }
                }
            }
        }
    }

    private val mutablePlayer = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = mutablePlayer.asStateFlow()

    private val mutableStatus = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Idle)
    val status: StateFlow<PlaybackStatus> = mutableStatus.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshStatus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshStatus()

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) recordSuccess() }

        override fun onTracksChanged(tracks: Tracks) { recordSuccess() }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
            recordSuccess()
        }

        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            audioDecoder = null
        }
    }

    private fun describe(format: Format): String =
        "${format.sampleMimeType ?: "?"}/${format.codecs ?: "?"} ch=${format.channelCount} bps=${format.bitrate}"

    private fun selectedFormats(): String = listOfNotNull(
        exo?.audioFormat?.let { "A ${describe(it)}" },
        exo?.videoFormat?.let { "V ${describe(it)}" },
    ).joinToString("; ")

    private fun availableFormats(): String = exo?.currentTracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group -> (0 until group.length).map { index ->
            "${if (group.isTrackSelected(index)) "*" else ""}${describe(group.getTrackFormat(index))}" +
                if (group.isTrackSupported(index)) "" else " [nieobsługiwany]"
        } }.joinToString("; ")

    private fun diagnostic(error: PlaybackException? = null): String {
        val request = activeRequest ?: return ""
        val mode = (if (preview) "preview/" else "") + if (request.archive) "archive" else "live"
        return PlaybackDiagnosticRecord.text(request.url, mode, error?.errorCodeName, error,
            selectedFormats(), availableFormats(), audioDecoder)
    }

    private fun recordSuccess() {
        if (activeRequest == null || exo?.isPlaying != true) return
        val signature = "$audioDecoder|${selectedFormats()}|$preview"
        if (signature == successfulSignature) return
        successfulSignature = signature
        recordPlayback(false, diagnostic())
    }

    /** Plays [request]. A request for the stream that is already active does nothing. */
    fun play(request: StreamRequest) {
        if (gate.onRequest(request.url) == StreamGate.Action.Ignore) return
        activeRequest = request
        successfulSignature = null
        retries = 0
        fallbackUsed = false
        startJob?.cancel()
        httpFactory.setUserAgent(request.userAgent)

        val player = ensurePlayer()
        player.stop() // first close the old connection ...
        player.clearMediaItems()
        val session = connectionGate.nextSession()
        activeSession = session
        trace("STOP before switch")
        mutableStatus.value = PlaybackStatus.Connecting

        startJob = scope.launch {
            delay(CLOSE_GRACE_MS) // ... and give the server time to notice, only then open the new one
            awaitConnectionClose()
            trace("PREPARE session=$session player=${System.identityHashCode(player)} stream=${request.url.hashCode()}")
            setStream(player, mediaItem(request.url), trackedFactory(session))
            player.playWhenReady = true
            player.prepare()
        }
    }

    /** Closes the stream for good. */
    fun stop() {
        startJob?.cancel()
        startJob = null
        activeRequest = null
        suspendedRequest = null
        gate.onStopped()
        connectionGate.nextSession()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutableStatus.value = PlaybackStatus.Idle
        trace("STOP closed")
    }

    fun setPreview(enabled: Boolean) {
        if (preview == enabled) return
        preview = enabled
        exo?.let(::applyQuality)
        recordSuccess()
        trace("QUALITY preview=$enabled")
    }

    private fun applyQuality(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*AudioPreferences.languages(listOf(Locale.getDefault())).toTypedArray())
            .setMaxVideoSize(if (preview) 960 else Int.MAX_VALUE, if (preview) 540 else Int.MAX_VALUE)
            .setMaxVideoBitrate(if (preview) 1_500_000 else Int.MAX_VALUE)
            .build()
    }

    private suspend fun awaitConnectionClose() {
        while (connections.get() > 0) delay(25)
    }

    /** Called when the app goes to the background: closes the stream but remembers it. */
    fun suspendStream() {
        val request = activeRequest ?: suspendedRequest
        stop()
        suspendedRequest = request
    }

    /** Called when the app returns: reconnects the stream that was running before, if any. */
    fun resumeStream() {
        val request = suspendedRequest ?: return
        suspendedRequest = null
        play(request)
    }

    /** Frees the decoder. The player is created again when needed. */
    fun releasePlayer() {
        val request = suspendedRequest
        stop()
        suspendedRequest = request
        mutablePlayer.value = null
        exo?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        exo = null
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

    fun seekTo(positionMs: Long): Boolean {
        val player = exo ?: return false
        if (!player.isCurrentMediaItemSeekable) return false
        player.seekTo(positionMs.coerceAtLeast(0))
        return true
    }

    fun seekToLiveEdge() {
        exo?.seekToDefaultPosition()
    }

    fun snapshot(): PlaybackSnapshot {
        val player = exo ?: return PlaybackSnapshot.EMPTY
        return PlaybackSnapshot(
            seekable = player.isCurrentMediaItemSeekable,
            liveOffsetMs = player.currentLiveOffset.takeIf { it != C.TIME_UNSET },
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET },
            windowStartMs = if (player.currentTimeline.isEmpty) null else
                player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    .windowStartTimeMs.takeIf { it != C.TIME_UNSET },
        )
    }

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val player = createPlayer(trackedFactory(0))
        applyQuality(player)
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
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
        recordPlayback(true, diagnostic(error))
        if (retries < MAX_RETRIES) {
            retries++
            val request = activeRequest ?: return
            val session = activeSession
            val action = PlaybackRecovery.action(error.errorCode, request.url, fallbackUsed, error.cause)
            if (action == RecoveryAction.Progressive) fallbackUsed = true
            mutableStatus.value = PlaybackStatus.Connecting
            startJob?.cancel()
            exo?.stop()
            startJob = scope.launch {
                delay(if (action == RecoveryAction.Retry) RETRY_DELAY_MS * retries else CLOSE_GRACE_MS)
                awaitConnectionClose()
                val player = exo ?: return@launch
                if (activeRequest !== request || activeSession != session) return@launch
                when (action) {
                    RecoveryAction.LiveEdge -> player.seekToDefaultPosition()
                    RecoveryAction.Progressive -> setStream(player, progressiveItem(request.url), trackedFactory(session))
                    RecoveryAction.Retry -> Unit
                }
                trace("RECOVER session=$session action=$action")
                player.prepare()
            }
        } else {
            mutableStatus.value = PlaybackStatus.Failed(error.errorCodeName)
            activeRequest = null
            gate.onStopped() // lets the user try the same channel again
            exo?.stop()
        }
    }

    private companion object {
        fun diagnosticRecorder(context: Context, scope: CoroutineScope): (Boolean, String) -> Unit {
            val store = PlaybackDiagnosticsStore(context.filesDir)
            val records = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
            scope.launch(Dispatchers.IO) {
                for ((failed, record) in records) runCatching { store.write(failed, record) }
            }
            return { failed, record -> records.trySend(failed to record); Unit }
        }

        const val CLOSE_GRACE_MS = 400L
        const val RETRY_DELAY_MS = 1_500L
        const val MAX_RETRIES = 3
    }
}
