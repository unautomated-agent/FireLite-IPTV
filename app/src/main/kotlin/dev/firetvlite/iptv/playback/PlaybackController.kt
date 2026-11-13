package dev.firetvlite.iptv.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.net.URI

/** What to play. */
data class StreamRequest(
    val url: String,
    val userAgent: String,
    val archive: Boolean = false,
    val channelName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val channelKey: String = DeadChannels.key(url),
    val userInitiated: Boolean = true,
)

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Connecting : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data object Paused : PlaybackStatus
    data object Ended : PlaybackStatus
    data class Failed(val code: String, val httpStatus: Int? = null) : PlaybackStatus
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
    val isLive: Boolean = false,
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
    private val preparationError: (Throwable) -> PlaybackException = {
        PlaybackException("Nie udało się przygotować odtwarzania", it, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)
    },
    private val startupTimeoutMs: Long = 10_000L,
    private val evictConnections: () -> Unit = { PlaybackHttp.evictIdle() },
    private val channelResult: (StreamRequest, String?) -> Unit = { _, _ -> },
    private val healthResult: (StreamRequest, String?) -> Unit = { _, _ -> },
    private val healthSummary: (String) -> Unit = {},
    private val healthProbe: (StreamRequest, (okhttp3.Call?) -> Unit, () -> Unit) -> String? = { request, call, active ->
        ChannelHealthProbe().check(request, call, active)
    },
) {
    internal constructor(scope: CoroutineScope, createPlayer: (HttpDataSource.Factory) -> ExoPlayer) :
        this(scope, createPlayer, {})

    internal constructor(context: Context, scope: CoroutineScope, deadChannels: DeadChannels) : this(scope, { httpFactory ->
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(PlaybackMediaSourceFactory(httpFactory))
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages(*AudioPreferences.languages(listOf(Locale.getDefault())).toTypedArray())
                    .build()
            }
    }, { Log.d("FireLiteStream", it) }, setStream = { player, item, factory ->
        player.setMediaSource(PlaybackMediaSourceFactory(factory).createMediaSource(item))
    }, recordPlayback = diagnosticRecorder(context, scope), channelResult = { request, reason -> deadChannels.result(request, reason) },
        healthResult = { request, reason -> deadChannels.result(request, reason, "health check") },
        healthSummary = deadChannels::checkSummary)

    private val gate = StreamGate()
    private val evictionMutex = Mutex()
    private var exo: ExoPlayer? = null
    private var startJob: Job? = null
    private var startupJob: Job? = null
    private var progressJob: Job? = null
    private var healthJob: Job? = null
    @Volatile private var healthCall: okhttp3.Call? = null
    private var healthGeneration = 0L
    private val mutableHealthCheck = MutableStateFlow(ChannelCheckProgress())
    val healthCheck: StateFlow<ChannelCheckProgress> = mutableHealthCheck.asStateFlow()
    private var rendered = false
    private var startupFailureMarked = false
    private val rendererReadiness = mutableMapOf<Int, Boolean>()
    private var startupTiming: StartupTiming? = null
    private var retries = 0
    @Volatile private var activeSession = 0L
    @Volatile private var lastHttp: Pair<Long, HttpRequestEvidence>? = null
    @Volatile private var lastHttpFailure: Pair<Long, HttpRequestEvidence>? = null
    private var sourceType = "auto"
    private var startupError: PlaybackException? = null
    private var hlsSession = HlsSession()
    private var textDisabledBeforeFailover: Boolean? = null
    private var startupTimedOut = false
    private var recovery = PlaybackRecovery()
    private var activeRequest: StreamRequest? = null
    private var suspendedRequest: StreamRequest? = null
    private var preview = false
    private var audioDecoder: String? = null
    private var successfulSignature: String? = null
    private val connections = AtomicInteger()
    private val connectionGate = HttpConnectionGate()
    private fun trackedFactory(session: Long) = object : HlsSessionFactory {
        override val hls = hlsSession
        private val request = activeRequest
        private var headers = request?.headers.orEmpty()
        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
            headers = properties
            return this
        }

        override fun createDataSource(dataType: Int): HttpDataSource {
            var requestId = 0L
            val calls = PlaybackHttp.calls(request?.url.orEmpty(), dataType,
                channelHeaders = request?.headers.orEmpty(), userAgent = PlaybackHttp.userAgent(headers, request?.userAgent),
                onTransport = { snapshot ->
                    if (session == activeSession && hls.transport(requestId, snapshot) &&
                        (snapshot.status?.let { it >= 400 } == true || snapshot.phase == TransportPhase.Failed)) {
                        scope.launch {
                            if (session == activeSession && activeRequest === request)
                                recordPlayback(true, diagnostic(code = if (startupTimedOut) "START_TIMEOUT" else "PROVIDER_RESPONSE",
                                    details = startupDetails()))
                        }
                    }
                }) { evidence ->
                if (session == activeSession) {
                    hls.response(requestId, evidence.status)
                    lastHttp = session to evidence
                    if (evidence.status >= 400) lastHttpFailure = session to evidence
                }
            }
            val delegate = OkHttpDataSource.Factory(calls)
                .setUserAgent(PlaybackHttp.userAgent(headers, request?.userAgent))
                .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
                .createDataSource()
            return object : HttpDataSource by delegate {
                private var connected = false
                private var stream = 0
                private var connection: Closeable? = null

                override fun open(dataSpec: DataSpec): Long {
                    connection = connectionGate.acquire(session)
                    requestId = hls.begin(dataSpec.uri.toString(), dataType)
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
                            hls.end(requestId)
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
    private val mutableStartupDelayed = MutableStateFlow(false)
    val startupDelayed: StateFlow<Boolean> = mutableStartupDelayed.asStateFlow()
    private val mutableProviderWaiting = MutableStateFlow(false)
    val providerWaiting: StateFlow<Boolean> = mutableProviderWaiting.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = refreshStatus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = refreshStatus()

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { renderingStarted(); recordSuccess() }
        }

        override fun onTracksChanged(tracks: Tracks) { recordSuccess() }

        override fun onRenderedFirstFrame() {
            if (exo?.playbackState == Player.STATE_READY) renderingStarted()
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onRendererReadyChanged(eventTime: AnalyticsListener.EventTime, rendererIndex: Int,
            trackType: Int, isRendererReady: Boolean) {
            rendererReadiness[trackType] = isRendererReady
        }
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            val player = exo ?: return
            if (player.playbackState == Player.STATE_READY && player.audioFormat != null &&
                player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) renderingStarted()
        }
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData) {
            if (!hlsSession.owns(loadEventInfo.dataSpec.uri.toString())) return
            if (activeRequest != null) hlsSession.loaded(loadEventInfo.uri.toString())
            if (activeRequest != null && mediaLoadData.dataType == C.DATA_TYPE_MANIFEST)
                recovery.onManifestLoaded()
        }

        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            if (mediaLoadData.trackType in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_DEFAULT, C.TRACK_TYPE_AUDIO))
                mediaLoadData.trackFormat?.let { hlsSession.selected(it) }
        }

        override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
            if (wasCanceled) return
            val request = activeRequest ?: return
            val status = PlaybackHttpError.status(error) ?: return
            val failedUri = PlaybackHttpError.requestUri(error) ?: loadEventInfo.uri.toString()
            if (!hlsSession.owns(failedUri)) return
            if (status in 400..599) markStartupFailure("HTTP $status")
            hlsSession.failedRequest(failedUri, status)
            val kind = hlsSession.kind(failedUri, mediaLoadData.dataType)
            val player = exo ?: return
            if (status in setOf(404, 410)) when {
                kind == "subs" -> {
                    if (textDisabledBeforeFailover == null)
                        textDisabledBeforeFailover = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    hlsSession.fallback(failedUri)
                }
                kind == "audio" -> {
                    val muxed = player.currentTracks.groups.firstOrNull {
                        it.type == C.TRACK_TYPE_AUDIO && it.mediaTrackGroup.id == "main:audio" &&
                            (0 until it.length).any(it::isTrackSupported)
                    }
                    muxed?.let { group ->
                        val track = (0 until group.length).first(group::isTrackSupported)
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track)).build()
                        hlsSession.fallback(failedUri)
                    }
                }
                !request.archive && kind == "segment" && player.isCurrentMediaItemLive &&
                    mediaLoadData.mediaEndTimeMs != C.TIME_UNSET -> {
                    val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
                    val next = mediaLoadData.mediaEndTimeMs - window.positionInFirstPeriodMs + 1
                    if (next > player.currentPosition && (window.durationMs == C.TIME_UNSET || next < window.durationMs) &&
                        hlsSession.skip(failedUri)) player.seekTo(next)
                }
            }
            if (!rendered || startupTimedOut) {
                val failure = PlaybackException("HTTP load failed", error, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                recordPlayback(true, diagnostic(failure, code = if (startupTimedOut) "START_TIMEOUT" else "HLS_LOAD_HTTP_$status",
                    details = startupDetails()))
            }
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
            recordSuccess()
        }

        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            audioDecoder = null
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?) { recordSuccess() }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?) { recordSuccess() }
    }

    private fun describe(format: Format): String =
        "${format.sampleMimeType ?: "?"}/${format.codecs ?: "?"} ch=${format.channelCount} sr=${format.sampleRate} bps=${format.bitrate}" +
            if (format.width > 0) " ${format.width}x${format.height}@${format.frameRate}" else ""

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

    private fun diagnostic(error: PlaybackException? = null, code: String? = error?.errorCodeName, details: List<String> = emptyList()): String {
        val request = activeRequest ?: return ""
        val mode = (if (preview) "preview/" else "") + if (request.archive) "archive" else "live"
        val evidence = httpEvidence()
        val status = PlaybackHttpError.status(error) ?: evidence?.status
        val http = if (status != null && status != evidence?.status) listOf("HTTP: HTTP=$status")
            else evidence?.lines() ?: listOf("HTTP: not available")
        return PlaybackDiagnosticRecord.text(request.url, mode, code, error,
            selectedFormats(), availableFormats(), audioDecoder, details =
                http +
                    listOfNotNull(error?.let {
                        "Screen: ${PlaybackHttpError.message(it.errorCodeName, it, httpEvidence()?.status)}; code=${it.errorCodeName}"
                    }) + details + if (sourceType == "HLS" || hlsSession.hasPlaylist || hlsSession.hasManifestRequest)
                        hlsSession.lines() else emptyList())
    }

    private fun startupDetails(): List<String> = startupTiming?.let { timing ->
        val player = exo
        listOf("Channel: ${activeRequest?.channelName.orEmpty()}", *timing.detail().lines().toTypedArray(),
            *TimeoutEvidence.lines(player, rendererReadiness).toTypedArray(),
            "Player: ${player?.playbackState}; pos=${player?.currentPosition}; buffered=${player?.bufferedPosition}; openHTTP=${connections.get()}",
            "Source: $sourceType")
    }.orEmpty()

    private fun httpEvidence(): HttpRequestEvidence? =
        lastHttpFailure?.takeIf { it.first == activeSession }?.second
            ?: lastHttp?.takeIf { it.first == activeSession }?.second

    private fun failed(error: PlaybackException): PlaybackStatus.Failed = PlaybackStatus.Failed(error.errorCodeName,
        PlaybackHttpError.displayedStatus(error.errorCodeName, error, httpEvidence()?.status))

    private fun renderingStarted() {
        if (activeRequest == null) return
        if (!rendered) activeRequest?.let { channelResult(it, null) }
        rendered = true
        startupTimedOut = false
        lastHttpFailure = null
        startupError = null
        retries = 0
        clearStartupWatch()
    }

    private fun clearProgressWatch() {
        progressJob?.cancel()
        progressJob = null
        mutableStartupDelayed.value = false
    }

    private fun progressPosition(player: ExoPlayer): Long = player.currentPosition +
        if (player.currentTimeline.isEmpty) 0 else player.currentTimeline
            .getWindow(player.currentMediaItemIndex, Timeline.Window()).positionInFirstPeriodMs

    private fun watchProgress(code: String) {
        val request = activeRequest ?: return
        val player = exo ?: return
        clearProgressWatch()
        progressJob = scope.launch {
            var position = progressPosition(player)
            var since = System.nanoTime()
            var reported = false
            while (activeRequest === request) {
                delay(minOf(250L, startupTimeoutMs.coerceAtLeast(1)))
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) break
                val next = progressPosition(player)
                if (player.playWhenReady && player.playbackState == Player.STATE_READY && next > position) break
                if (!player.playWhenReady) {
                    since = System.nanoTime()
                    mutableStartupDelayed.value = false
                } else if (!reported && (System.nanoTime() - since) / 1_000_000 >= startupTimeoutMs) {
                    recordPlayback(true, diagnostic(code = code, details = listOf(
                        "Channel: ${request.channelName.ifBlank { "unknown" }}",
                        *TimeoutEvidence.lines(player, rendererReadiness).toTypedArray(),
                        "Player: ${player.playbackState}; pos=${player.currentPosition}; buffered=${player.bufferedPosition}",
                        "Source: $sourceType", "openHTTP=${connections.get()}",
                    )))
                    mutableStartupDelayed.value = true
                    reported = true
                }
                position = next
            }
            mutableStartupDelayed.value = false
            progressJob = null
        }
    }

    private fun clearStartupWatch() {
        startupJob?.cancel()
        startupJob = null
        startupTiming = null
        mutableStartupDelayed.value = false
        mutableProviderWaiting.value = false
    }

    private fun watchStartup(request: StreamRequest) {
        clearStartupWatch()
        val timing = StartupTiming()
        startupTiming = timing
        startupTimedOut = false
        val since = System.nanoTime()
        startupJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var reported = false
            while (activeRequest === request) {
                val elapsed = (System.nanoTime() - since) / 1_000_000
                val deadline = hlsSession.timeoutAt(startupTimeoutMs)
                mutableProviderWaiting.value = hlsSession.waitingForProvider()
                if (!reported && elapsed >= deadline) {
                    reported = true
                    if (!timing.waitingForPrevious) markStartupFailure(
                        if (hlsSession.providerTimeout() != null) "no response 45s" else "timeout")
                    startupTimedOut = true
                    val player = exo
                    val state = when (player?.playbackState) {
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "IDLE"
                    }
                    recordPlayback(true, diagnostic(startupError, code = "START_TIMEOUT", details = listOf(
                        "Channel: ${request.channelName.ifBlank { "unknown" }}",
                        *TimeoutEvidence.lines(player, rendererReadiness).toTypedArray(),
                        *timing.detail().lines().toTypedArray(),
                        if (timing.waitingForPrevious)
                            "Ownership: previous session connections/player/tracks; requested stream not prepared"
                        else "Ownership: requested stream session",
                        "Player: $state; pos=${player?.currentPosition}; buffered=${player?.bufferedPosition}; openHTTP=${connections.get()}",
                        "Source: $sourceType",
                    ) + listOfNotNull(hlsSession.providerTimeout())))
                    mutableStartupDelayed.value = true
                }
                delay(if (reported) 250 else minOf(250, (deadline - elapsed).coerceAtLeast(1)))
            }
        }
    }

    private fun recordSuccess() {
        if (activeRequest == null || exo?.isPlaying != true) return
        val signature = "$audioDecoder|${selectedFormats()}|$preview"
        if (signature == successfulSignature) return
        successfulSignature = signature
        recordPlayback(false, diagnostic())
    }

    fun audioTracks(): List<AudioTrackChoice> = exo?.currentTracks?.groups.orEmpty().flatMapIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) emptyList() else (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) null else group.getTrackFormat(index).let { format ->
                AudioTrackChoice(groupIndex, index, format.language, format.label,
                    format.codecs ?: format.sampleMimeType, group.isTrackSelected(index),
                    format.roleFlags, format.selectionFlags, format.channelCount)
            }
        }
    }

    fun selectAudioTrack(choice: AudioTrackChoice): Boolean {
        val player = exo ?: return false
        val group = player.currentTracks.groups.getOrNull(choice.group) ?: return false
        if (!AudioTrackSelection.valid(group.type == C.TRACK_TYPE_AUDIO, group.length, choice.index,
                choice.index in 0 until group.length && group.isTrackSupported(choice.index))) return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.index)))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
        return true
    }

    fun playArchiveFromStart(request: StreamRequest) {
        val player = exo
        if (request.archive && request == activeRequest && player != null && startJob?.isActive != true) {
            player.seekTo(0L)
            watchProgress("SEEK_TIMEOUT")
        } else {
            play(request)
        }
    }

    /** Plays [request]. A request for the stream that is already active does nothing. */
    fun play(request: StreamRequest) {
        if (gate.onRequest(request.url) == StreamGate.Action.Ignore) return
        stopHealthCheck()
        val previousCheck = healthJob
        clearProgressWatch()
        rendered = false
        startupFailureMarked = false
        rendererReadiness.clear()
        activeRequest = request
        successfulSignature = null
        retries = 0
        recovery = PlaybackRecovery()
        startJob?.cancel()
        sourceType = when (runCatching { URI(request.url).path.substringAfterLast('.').lowercase() }.getOrNull()) {
            "m3u8" -> "HLS"
            "mpd" -> "DASH"
            else -> "auto/progressive"
        }
        lastHttp = null
        lastHttpFailure = null
        startupError = null
        hlsSession = HlsSession(request.archive)
        watchStartup(request)

        val player = prepareSafely { ensurePlayer() } ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).apply {
                textDisabledBeforeFailover?.let { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it) }
            }.build()
        textDisabledBeforeFailover = null
        player.stop() // first close the old connection ...
        player.clearMediaItems()
        val session = connectionGate.nextSession()
        activeSession = session
        trace("STOP before switch")
        mutableStatus.value = PlaybackStatus.Connecting

        startupTiming?.enter(StartupTiming.Phase.WAITING_PREVIOUS_CONNECTION)

        startJob = scope.launch {
            previousCheck?.join()
            awaitConnectionClose()
            if (activeRequest !== request || activeSession != session) return@launch
            if (prepareSafely {
                withContext(Dispatchers.IO) { evictionMutex.withLock { evictConnections() } }
                true
            } != true) return@launch
            if (activeRequest !== request || activeSession != session) return@launch
            hlsSession.evicted()
            startupTiming?.enter(StartupTiming.Phase.CLOSE_GRACE)
            delay(CLOSE_GRACE_MS)
            startupTiming?.enter(StartupTiming.Phase.PREPARING_BUFFERING)
            trace("PREPARE session=$session player=${System.identityHashCode(player)} stream=${request.url.hashCode()}")
            prepareSafely {
                setStream(player, mediaItem(request.url), trackedFactory(session))
                player.playWhenReady = true
                player.prepare()
            }
        }
    }

    private inline fun <T> prepareSafely(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failPreparation(error)
        null
    } catch (error: LinkageError) {
        failPreparation(error)
        null
    }

    private fun failPreparation(cause: Throwable) {
        markStartupFailure("ERROR_CODE_FAILED_RUNTIME_CHECK")
        clearProgressWatch()
        clearStartupWatch()
        val error = preparationError(cause)
        recordPlayback(true, diagnostic(error, details = startupDetails()))
        activeRequest = null
        gate.onStopped()
        mutableStatus.value = failed(error)
        runCatching { exo?.stop() }
    }

    /** Closes the stream for good. */
    fun stop() {
        stopHealthCheck()
        clearProgressWatch()
        rendered = false
        clearStartupWatch()
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
            .setMaxVideoSize(if (preview) 960 else Int.MAX_VALUE, if (preview) 540 else Int.MAX_VALUE)
            .setMaxVideoBitrate(if (preview) 1_500_000 else Int.MAX_VALUE)
            .build()
    }

    private suspend fun awaitConnectionClose() {
        while (connections.get() > 0) delay(25)
    }

    fun startHealthCheck(scopeName: String, total: Int, load: suspend (Int) -> StreamRequest?) {
        val previous = healthJob
        stop()
        val session = connectionGate.nextSession()
        activeSession = session
        val generation = ++healthGeneration
        mutableHealthCheck.value = ChannelCheckProgress(running = true, scope = DeadChannels.safeName(scopeName), total = total)
        publishHealthSummary("running")
        healthJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                for (index in 0 until total) {
                    ensureActive()
                    val channel = withContext(Dispatchers.IO) { load(index) } ?: throw IOException("Channel unavailable")
                    mutableHealthCheck.value = mutableHealthCheck.value.copy(current = DeadChannels.safeName(channel.channelName, channel.url))
                    awaitConnectionClose()
                    withContext(Dispatchers.IO) { evictionMutex.withLock { evictConnections() } }
                    delay(CLOSE_GRACE_MS)
                    val reason = withContext(Dispatchers.IO) {
                        connectionGate.acquire(session).use {
                            connections.incrementAndGet()
                            try { healthProbe(channel, { healthCall = it }, { ensureActive() }) }
                            finally { connections.decrementAndGet() }
                        }
                    }
                    ensureActive()
                    healthResult(channel, reason)
                    val current = mutableHealthCheck.value
                    mutableHealthCheck.value = current.copy(checked = current.checked + 1,
                        dead = current.dead + if (reason == null) 0 else 1,
                        healthy = current.healthy + if (reason == null) 1 else 0)
                    publishHealthSummary("running")
                    if (index + 1 < total) delay(2_000)
                }
                mutableHealthCheck.value = mutableHealthCheck.value.copy(complete = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableHealthCheck.value = mutableHealthCheck.value.copy(error = true)
            } finally {
                if (generation == healthGeneration) {
                    mutableHealthCheck.value = mutableHealthCheck.value.copy(running = false, current = "")
                    publishHealthSummary(if (mutableHealthCheck.value.complete) "complete" else "stopped")
                }
            }
        }
        healthJob?.start()
    }

    fun stopHealthCheck() {
        healthJob?.cancel()
        val call = healthCall
        if (call != null) scope.launch(Dispatchers.IO) { call.cancel() }
        mutableHealthCheck.value = mutableHealthCheck.value.copy(running = false)
    }

    private fun publishHealthSummary(outcome: String) {
        val current = mutableHealthCheck.value
        val time = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.of("Europe/Warsaw")).format(java.time.Instant.now())
        healthSummary("$time · ${utf8Limit(current.scope, 18)} · ${current.checked}/${current.total} · martwe ${current.dead} · $outcome")
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
        play(request.copy(userInitiated = false))
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
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        player.seekTo(positionMs.coerceIn(0, duration?.minus(1) ?: Long.MAX_VALUE))
        watchProgress("SEEK_TIMEOUT")
        return true
    }

    fun seekToLiveEdge() {
        exo?.seekToDefaultPosition()
        watchProgress("SEEK_TIMEOUT")
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
            isLive = player.isCurrentMediaItemLive,
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
            Player.STATE_BUFFERING -> {
                mutableStatus.value = PlaybackStatus.Buffering
                if (rendered && progressJob == null) watchProgress("REBUFFER_TIMEOUT")
            }
            Player.STATE_READY -> {
                recovery.onReady()
                mutableStatus.value =
                    if (player.playWhenReady) PlaybackStatus.Playing else PlaybackStatus.Paused
            }
            Player.STATE_ENDED -> mutableStatus.value = PlaybackStatus.Ended
            else -> Unit // IDLE: keep Connecting or Failed
        }
    }

    private fun handleError(error: PlaybackException) {
        if (activeRequest == null) return
        markStartupFailure(PlaybackHttpError.status(error)?.let { "HTTP $it" } ?: error.errorCodeName)
        clearProgressWatch()
        startupError = error
        recordPlayback(true, diagnostic(error, details = startupDetails()))
        if (retries < MAX_RETRIES) {
            retries++
            val request = activeRequest ?: return
            val session = activeSession
            val action = recovery.action(error.errorCode, request.url, error.cause)
            mutableStatus.value = PlaybackStatus.Connecting
            startJob?.cancel()
            exo?.stop()
            startupTiming?.enter(StartupTiming.Phase.RECOVERY_DELAY)
            startJob = scope.launch {
                delay(if (action == RecoveryAction.Retry) RETRY_DELAY_MS * retries else CLOSE_GRACE_MS)
                startupTiming?.enter(StartupTiming.Phase.WAITING_RECOVERY_CONNECTION)
                awaitConnectionClose()
                val player = exo ?: return@launch
                if (activeRequest !== request || activeSession != session) return@launch
                startupTiming?.enter(StartupTiming.Phase.PREPARING_BUFFERING)
                prepareSafely {
                    recovery.onRecovery(action)
                    when (action) {
                        RecoveryAction.LiveEdge -> player.seekToDefaultPosition()
                        RecoveryAction.Progressive -> {
                            sourceType = "progressive/MP2T fallback"
                            setStream(player, progressiveItem(request.url), trackedFactory(session))
                        }
                        RecoveryAction.RestoreHls -> {
                            sourceType = "HLS"
                            setStream(player, mediaItem(request.url), trackedFactory(session))
                        }
                        RecoveryAction.Retry -> Unit
                    }
                    trace("RECOVER session=$session action=$action")
                    player.prepare()
                }
            }
        } else {
            clearStartupWatch()
            mutableStatus.value = failed(error)
            activeRequest = null
            gate.onStopped() // lets the user try the same channel again
            exo?.stop()
        }
    }

    private fun markStartupFailure(reason: String) {
        val request = activeRequest ?: return
        if (rendered || startupFailureMarked || !request.userInitiated) return
        startupFailureMarked = true
        channelResult(request, reason)
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
