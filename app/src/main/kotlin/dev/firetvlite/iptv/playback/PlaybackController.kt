package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.LaunchPhase

import dev.firetvlite.iptv.reporting.ReportSink
import dev.firetvlite.iptv.reporting.ReportText
import dev.firetvlite.iptv.data.library.DiagnosticRedactor

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
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.common.util.Util
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.net.URI
import dev.firetvlite.iptv.data.ChannelUsageKey
import dev.firetvlite.iptv.data.ChannelUsageSink

/** What to play. */
data class StreamRequest(
    val url: String,
    val userAgent: String,
    val archive: Boolean = false,
    val channelName: String = "",
    val headers: Map<String, String> = emptyMap(),
    val channelKey: String = DeadChannels.key(url),
    val userInitiated: Boolean = true,
    val coldStart: Boolean = false,
    val resumed: Boolean = false,
    val usageKey: ChannelUsageKey? = null,
    val countUsageSelection: Boolean = true,
)

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Connecting : PlaybackStatus
    data object Reconnecting : PlaybackStatus
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
    private val pooledConnections: () -> Int = { PlaybackHttp.pooled() },
    private val channelResult: (StreamRequest, String?) -> Unit = { _, _ -> },
    private val healthResult: (StreamRequest, String?) -> Unit = { _, _ -> },
    private val healthSummary: (String) -> Unit = {},
    private val recordStall: (PlaybackStall) -> Unit = {},
    private val healthProbe: (StreamRequest, (okhttp3.Call?) -> Unit, () -> Unit) -> String? = { request, call, active ->
        ChannelHealthProbe().check(request, call, active)
    },
    private val reports: ReportSink = ReportSink.None,
    private val launchEvent: (LaunchPhase) -> Unit = {},
    private val reconnectDelayMs: Long = 2_000L,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val loudness: LoudnessLeveller? = null,
    usageSink: ChannelUsageSink = ChannelUsageSink.None,
) {
    internal constructor(scope: CoroutineScope, createPlayer: (HttpDataSource.Factory) -> ExoPlayer) :
        this(scope, createPlayer, {})

    internal constructor(context: Context, scope: CoroutineScope, deadChannels: DeadChannels, stalls: StallHistory,
        reports: ReportSink = ReportSink.None, loudness: LoudnessLeveller? = null,
        launchEvent: (LaunchPhase) -> Unit = {}, usageSink: ChannelUsageSink = ChannelUsageSink.None) : this(scope, { httpFactory ->
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
        healthSummary = deadChannels::checkSummary, recordStall = stalls::record, reports = reports,
        loudness = loudness, launchEvent = launchEvent, usageSink = usageSink)

    private val gate = StreamGate()
    private val usage = PlaybackUsage(monotonicMs, usageSink)
    private var usageJob: Job? = null
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
    @Volatile private var rendered = false
    @Volatile private var reportSwitching = false
    @Volatile private var reportSteadyAfter = Long.MAX_VALUE
    internal val reportUploadsAllowed: Boolean
        get() = !reportSwitching && when (status.value) {
            PlaybackStatus.Connecting, PlaybackStatus.Reconnecting, PlaybackStatus.Buffering -> false
            PlaybackStatus.Playing, PlaybackStatus.Paused -> rendered && System.nanoTime() >= reportSteadyAfter
            else -> true
        }
    private var sessionFirstFrame = false
    private var stallJob: Job? = null
    private val lastMediaByteMs = AtomicLong(0)
    private val mediaLoads = ConcurrentHashMap<Long, Long>()
    private var lastLoadMs = 0L
    private var lastLoadAt = 0L
    private var bandwidth = 0L
    private var loadErrorAt = 0L
    private var loadErrorStatus: Int? = null
    private var decoderErrorAt = 0L
    private val mutableFirstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = mutableFirstFrame.asStateFlow()
    private var startupFailureMarked = false
    private var startupUserInitiated = false
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
    private data class ReconnectAttempt(val request: StreamRequest, val error: PlaybackException)
    private var reconnectAttempt: ReconnectAttempt? = null
    private var reconnectUsed = false
    private var reachedReady = false
    private var preparedCurrent = false
    private var stableSince: Long? = null
    internal val hasActiveStream: Boolean get() = activeRequest != null
    private var suspendedRequest: StreamRequest? = null
    private var suspendedReconnectUsed = false
    private var preview = false
    private var audioDecoder: String? = null
    private var successfulSignature: String? = null
    private val connections = AtomicInteger()
    private val createdAt = monotonicMs()
    /** When the last stream request of this process ended; null while there has been none. */
    @Volatile private var lastRequestClosedAt: Long? = null
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
                                saveDiagnostic(true, diagnostic(code = if (startupTimedOut) "START_TIMEOUT" else "PROVIDER_RESPONSE",
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
                    if (dataType == C.DATA_TYPE_MEDIA) mediaLoads[requestId] = System.nanoTime() / 1_000_000
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
                            mediaLoads.remove(requestId)
                            lastRequestClosedAt = monotonicMs()
                            trace("HTTP_CLOSE session=$session stream=$stream active=${connections.decrementAndGet()}")
                            connection?.close()
                            connection = null
                        }
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val read = delegate.read(buffer, offset, length)
                    if (read > 0 && session == activeSession && dataType != C.DATA_TYPE_MANIFEST)
                        lastMediaByteMs.set(System.nanoTime() / 1_000_000)
                    return read
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
            updateUsage(isPlaying && exo?.isPlaying == true)
            if (isPlaying) { livePlaying(); renderingStarted(); recordSuccess() } else endStablePlayback()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) endStablePlayback()
        }

        override fun onTracksChanged(tracks: Tracks) { recordSuccess() }

        override fun onRenderedFirstFrame() {
            if (activeRequest?.coldStart == true) launchEvent(LaunchPhase.FirstFrame)
            mutableFirstFrame.value = true
            sessionFirstFrame = true
            if (exo?.playbackState == Player.STATE_READY) renderingStarted()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (rendered) watchStalls()
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            loudness?.sessionChanged(audioSessionId)
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            exo?.let { loudness?.sessionChanged(it.audioSessionId) }
            loudness?.outputChanged(Util.isEncodingLinearPcm(audioTrackConfig.encoding) &&
                !audioTrackConfig.offload && !audioTrackConfig.tunneling)
        }

        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            loudness?.trackReleased()
        }

        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int,
            totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidth = bitrateEstimate }

        override fun onRendererReadyChanged(eventTime: AnalyticsListener.EventTime, rendererIndex: Int,
            trackType: Int, isRendererReady: Boolean) {
            rendererReadiness[trackType] = isRendererReady
        }
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            val player = exo ?: return
            if (player.playbackState == Player.STATE_READY && player.audioFormat != null &&
                player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) {
                if (!sessionFirstFrame && activeRequest?.coldStart == true) launchEvent(LaunchPhase.FirstFrame)
                sessionFirstFrame = true
                mutableFirstFrame.value = true
                renderingStarted()
            }
        }
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData) {
            if (!hlsSession.owns(loadEventInfo.dataSpec.uri.toString())) return
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA || mediaLoadData.dataType == C.DATA_TYPE_UNKNOWN) {
                lastLoadMs = loadEventInfo.loadDurationMs
                lastLoadAt = System.nanoTime() / 1_000_000
                if (lastLoadMs > 0) bandwidth = loadEventInfo.bytesLoaded * 8_000 / lastLoadMs
            }
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
            loadErrorAt = System.nanoTime() / 1_000_000
            loadErrorStatus = status
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
                saveDiagnostic(true, diagnostic(failure, code = if (startupTimedOut) "START_TIMEOUT" else "HLS_LOAD_HTTP_$status",
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

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            decoderErrorAt = System.nanoTime() / 1_000_000
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            decoderErrorAt = System.nanoTime() / 1_000_000
        }
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

    private fun saveDiagnostic(failed: Boolean, record: String) {
        recordPlayback(failed, record)
        if (failed) runCatching {
            val request = activeRequest
            val known = DiagnosticRedactor.secrets(listOfNotNull(request?.url))
            reports.enqueue("playback_error", ReportText.safe(request?.channelName.orEmpty(), 120, known),
                record.lineSequence().firstOrNull().orEmpty().substringAfter(" · "), record)
        }
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
        if (!rendered) reportStart()
        if (!rendered) watchStalls()
        rendered = true
        livePlaying()
        startupTimedOut = false
        lastHttpFailure = null
        startupError = null
        retries = 0
        clearStartupWatch()
    }

    /** Sends how long the picture took after opening the app, returning to it, or whenever it took long. */
    private fun reportStart() {
        val request = activeRequest ?: return
        val timing = startupTiming ?: return
        val total = timing.totalMs()
        val trigger = when {
            request.coldStart -> "cold"
            request.resumed -> "resume"
            total >= SLOW_START_MS -> "slow"
            else -> return
        }
        runCatching {
            val known = DiagnosticRedactor.secrets(listOf(request.url))
            reports.enqueue("other", ReportText.safe(request.channelName, 120, known),
                "start: ${if (request.archive) "archive" else "live"} $trigger total=${total}ms",
                (timing.detail().lines() + hlsSession.lines().filter {
                    it.startsWith("HLS request") || it.startsWith("Transport")
                }).joinToString("\n"))
        }
    }

    private fun watchStalls() {
        stallJob?.cancel()
        val request = activeRequest ?: return
        val detector = StallDetector()
        val channel = DeadChannels.safeName(request.channelName, request.url)
        val known = DiagnosticRedactor.secrets(listOf(request.url))
        stallJob = scope.launch {
            var previousTick = System.nanoTime() / 1_000_000
            while (activeRequest === request) {
                delay(500)
                val player = exo ?: break
                val now = System.nanoTime() / 1_000_000
                val counters = player.videoDecoderCounters
                counters?.ensureUpdated()
                detector.sample(StallSample(now, System.currentTimeMillis(), channel,
                    active = rendered && sessionFirstFrame && player.playWhenReady && !player.isPlayingAd &&
                        player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING) &&
                        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
                    buffering = player.playbackState == Player.STATE_BUFFERING, ready = player.playbackState == Player.STATE_READY,
                    frames = if (player.videoFormat != null) counters?.renderedOutputBufferCount else null,
                    positionMs = progressPosition(player), bufferedMs = player.totalBufferedDuration,
                    byteAgeMs = if (lastMediaByteMs.get() == 0L) 0 else (now - lastMediaByteMs.get()).coerceAtLeast(0),
                    httpError = loadErrorStatus.takeIf { now - loadErrorAt in 0..10_000 },
                    loadMs = maxOf(mediaLoads.values.minOrNull()?.let { now - it } ?: 0,
                        lastLoadMs.takeIf { now - lastLoadAt in 0..10_000 } ?: 0),
                    bandwidth = bandwidth, dropped = counters?.droppedBufferCount ?: 0,
                    decoderError = now - decoderErrorAt in 0..10_000,
                    jankMs = if (PlaybackJank.atMs in previousTick..now)
                        maxOf(PlaybackJank.durationMs, now - previousTick) else 0,
                ))?.let { stall ->
                    recordStall(stall)
                    runCatching {
                        reports.midPlaybackStall(ReportText.safe(stall.channel, 120, known),
                            ReportText.safe("${stall.kind} · ${stall.evidence.substringBefore(';')}", 500, known),
                            ReportText.safe(stall.line(), 16_000, known), stall.time)
                    }
                }
                previousTick = now
            }
        }
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
                    saveDiagnostic(true, diagnostic(code = code, details = listOf(
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

    private fun watchStartup(request: StreamRequest, extraMs: Long = 0) {
        clearStartupWatch()
        val timing = StartupTiming()
        startupTiming = timing
        startupTimedOut = false
        val since = System.nanoTime() + extraMs * 1_000_000
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
                    saveDiagnostic(true, diagnostic(startupError, code = "START_TIMEOUT", details = listOf(
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
                    val attempt = reconnectAttempt
                    if (attempt != null) {
                        finishReconnect("failed")
                        stop()
                        mutableStatus.value = failed(attempt.error)
                        return@launch
                    }
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
        saveDiagnostic(false, diagnostic())
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
        cancelAutoReconnect()
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
        cancelAutoReconnect()
        val player = exo
        val current = activeRequest
        val sameStream = request.archive && current?.archive == true && request.url == current.url &&
            request.userAgent == current.userAgent && request.headers == current.headers
        if (sameStream && request.userInitiated) startupUserInitiated = true
        if (sameStream && player != null && startJob?.isActive != true) {
            player.seekTo(0L)
            watchProgress("SEEK_TIMEOUT")
        } else {
            play(request)
        }
    }

    /** Plays [request]. A request for the stream that is already active does nothing. */
    fun play(request: StreamRequest) {
        cancelAutoReconnect()
        playInternal(request)
    }

    private fun playInternal(request: StreamRequest, automatic: Boolean = false) {
        if (gate.onRequest(request.url) == StreamGate.Action.Ignore) return
        updateUsage(false)
        usage.request(request.usageKey, request.userInitiated && request.countUsageSelection, automatic)
        if (request.coldStart) launchEvent(LaunchPhase.Resume)
        if (!automatic) reconnectUsed = false
        reachedReady = false
        preparedCurrent = false
        stableSince = null
        reportSwitching = true
        stopHealthCheck()
        val previousCheck = healthJob
        clearProgressWatch()
        rendered = false
        stallJob?.cancel()
        sessionFirstFrame = false
        lastMediaByteMs.set(0)
        mediaLoads.clear()
        lastLoadAt = 0
        lastLoadMs = 0
        bandwidth = 0
        loadErrorAt = 0
        loadErrorStatus = null
        decoderErrorAt = 0
        startupFailureMarked = false
        startupUserInitiated = request.userInitiated
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
        watchStartup(request, if (automatic) reconnectDelayMs else 0)

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
        mutableStatus.value = if (automatic) PlaybackStatus.Reconnecting else PlaybackStatus.Connecting
        reportSwitching = false

        startupTiming?.enter(StartupTiming.Phase.WAITING_PREVIOUS_CONNECTION)

        startJob = scope.launch {
            previousCheck?.join()
            awaitConnectionClose()
            if (activeRequest !== request || activeSession != session) return@launch
            val pooled = prepareSafely {
                withContext(Dispatchers.IO) { evictionMutex.withLock { pooledConnections().also { evictConnections() } } }
            } ?: return@launch
            if (activeRequest !== request || activeSession != session) return@launch
            hlsSession.evicted()
            startupTiming?.enter(StartupTiming.Phase.CLOSE_GRACE)
            delay(closeGraceMs(pooled))
            if (automatic) delay(reconnectDelayMs)
            if (activeRequest !== request || activeSession != session) return@launch
            startupTiming?.enter(StartupTiming.Phase.PREPARING_BUFFERING)
            trace("PREPARE session=$session player=${System.identityHashCode(player)} stream=${request.url.hashCode()}")
            prepareSafely {
                setStream(player, mediaItem(request.url), trackedFactory(session))
                player.playWhenReady = true
                if (request.coldStart) launchEvent(LaunchPhase.Prepare)
                preparedCurrent = true
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
        finishReconnect("failed")
        markStartupFailure("ERROR_CODE_FAILED_RUNTIME_CHECK")
        clearProgressWatch()
        clearStartupWatch()
        val error = preparationError(cause)
        saveDiagnostic(true, diagnostic(error, details = startupDetails()))
        activeRequest = null
        gate.onStopped()
        mutableStatus.value = failed(error)
        reportSwitching = false
        runCatching { exo?.stop() }
    }

    /** Closes the stream for good. */
    fun stop() {
        updateUsage(false)
        usage.stop()
        finishReconnect("cancelled")
        stableSince = null
        reachedReady = false
        preparedCurrent = false
        reportSwitching = false
        stopHealthCheck()
        clearProgressWatch()
        rendered = false
        stallJob?.cancel()
        sessionFirstFrame = false
        clearStartupWatch()
        startJob?.cancel()
        startJob = null
        activeRequest = null
        suspendedRequest = null
        suspendedReconnectUsed = false
        gate.onStopped()
        connectionGate.nextSession()
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        mutableStatus.value = PlaybackStatus.Idle
        trace("STOP closed")
    }

    fun setPreview(enabled: Boolean, retainUsage: Boolean = true) {
        cancelAutoReconnect()
        if (preview && !enabled && !retainUsage) usage.stop()
        if (preview == enabled) return
        preview = enabled
        updateUsage(exo?.isPlaying == true)
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

    /**
     * The pause between the old stream's last socket closing and the new stream's first request.
     * Nothing can still be open when this process kept no socket and its last request ended longer ago
     * than the pool keeps an idle socket, so only the part of the pause not yet served remains.
     */
    private fun closeGraceMs(pooled: Int): Long {
        val now = monotonicMs()
        val closedAt = lastRequestClosedAt
        if (pooled > 0 || (closedAt != null && now - closedAt < POOL_IDLE_MS + CLOSE_GRACE_MS)) return CLOSE_GRACE_MS
        return (CLOSE_GRACE_MS - (now - (closedAt?.plus(POOL_IDLE_MS) ?: createdAt))).coerceIn(0, CLOSE_GRACE_MS)
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
                            finally { lastRequestClosedAt = monotonicMs(); connections.decrementAndGet() }
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
        val reconnecting = reconnectAttempt != null || suspendedReconnectUsed
        stop()
        suspendedRequest = request
        suspendedReconnectUsed = reconnecting && request != null
    }

    /** Called when the app returns: reconnects the stream that was running before, if any. */
    fun resumeStream() {
        val request = suspendedRequest ?: return
        suspendedRequest = null
        val reconnectSpent = suspendedReconnectUsed
        suspendedReconnectUsed = false
        play(request.copy(userInitiated = false, resumed = true))
        // The drop that was being retried already had its one automatic attempt.
        if (reconnectSpent) reconnectUsed = true
    }

    /** Frees the decoder. The player is created again when needed. */
    fun releasePlayer() {
        loudness?.release()
        val request = suspendedRequest
        val reconnectSpent = suspendedReconnectUsed
        stop()
        suspendedRequest = request
        suspendedReconnectUsed = reconnectSpent
        mutablePlayer.value = null
        exo?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        exo = null
    }

    fun togglePause() {
        cancelAutoReconnect()
        val player = exo ?: return
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        cancelAutoReconnect()
        exo?.pause()
    }

    fun resume() {
        cancelAutoReconnect()
        exo?.play()
    }

    fun seekTo(positionMs: Long): Boolean {
        cancelAutoReconnect()
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
        loudness?.sessionChanged(player.audioSessionId)
        exo = player
        mutablePlayer.value = player
        return player
    }

    private fun refreshStatus() {
        val player = exo ?: return
        if (activeRequest == null) return
        when (player.playbackState) {
            Player.STATE_BUFFERING -> {
                endStablePlayback()
                mutableStatus.value = if (reconnectAttempt != null) PlaybackStatus.Reconnecting else PlaybackStatus.Buffering
                if (rendered && progressJob == null) watchProgress("REBUFFER_TIMEOUT")
            }
            Player.STATE_READY -> {
                if (player.playWhenReady) livePlaying() else endStablePlayback()
                recovery.onReady()
                if (mutableStatus.value != PlaybackStatus.Playing && player.playWhenReady)
                    reportSteadyAfter = System.nanoTime() + 3_000_000_000L
                mutableStatus.value =
                    if (player.playWhenReady) PlaybackStatus.Playing else PlaybackStatus.Paused
            }
            Player.STATE_ENDED -> {
                endStablePlayback()
                val attempt = reconnectAttempt
                if (attempt != null) {
                    finishReconnect("failed")
                    stop()
                    mutableStatus.value = failed(attempt.error)
                } else mutableStatus.value = PlaybackStatus.Ended
            }
            else -> Unit // IDLE: keep Connecting or Failed
        }
    }

    private fun handleError(error: PlaybackException) {
        updateUsage(false)
        if (activeRequest == null) return
        val request = activeRequest!!
        val wasReady = reachedReady
        endStablePlayback()
        reportSwitching = true
        markStartupFailure(PlaybackHttpError.status(error)?.let { "HTTP $it" } ?: error.errorCodeName)
        clearProgressWatch()
        startupError = error
        saveDiagnostic(true, diagnostic(error, details = startupDetails()))
        if (reconnectAttempt != null || wasReady && !request.archive) {
            if (reconnectAttempt == null && !reconnectUsed && !request.archive && !mutableHealthCheck.value.running) {
                reconnectUsed = true
                reconnectAttempt = ReconnectAttempt(request, error)
                gate.onStopped()
                playInternal(request.copy(userInitiated = false), automatic = true)
            } else {
                finishReconnect("failed")
                stop()
                mutableStatus.value = failed(error)
            }
            return
        }
        if (retries < MAX_RETRIES) {
            retries++
            val request = activeRequest ?: return
            val session = activeSession
            val action = recovery.action(error.errorCode, request.url, error.cause)
            mutableStatus.value = PlaybackStatus.Connecting
            reportSwitching = false
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
            reportSwitching = false
            activeRequest = null
            gate.onStopped() // lets the user try the same channel again
            exo?.stop()
        }
    }

    private fun markStartupFailure(reason: String) {
        val request = activeRequest ?: return
        if (rendered || startupFailureMarked || !startupUserInitiated) return
        startupFailureMarked = true
        channelResult(request.copy(userInitiated = startupUserInitiated), reason)
    }

    private fun updateUsage(playing: Boolean) {
        val active = playing && !preview && activeRequest != null && preparedCurrent
        usage.playing(active)
        usageJob?.cancel()
        usageJob = if (active) scope.launch {
            while (true) {
                delay(15_000)
                usage.sample()
            }
        } else null
    }

    private fun livePlaying() {
        val player = exo ?: return
        if (activeRequest == null || !preparedCurrent || !player.playWhenReady || player.playbackState != Player.STATE_READY) return
        reachedReady = true
        if (player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            if (stableSince == null) stableSince = monotonicMs()
        } else endStablePlayback()
        finishReconnect("ok")
    }

    private fun endStablePlayback() {
        stableSince?.let { if (monotonicMs() - it >= 60_000) reconnectUsed = false }
        stableSince = null
    }

    private fun finishReconnect(outcome: String) {
        val attempt = reconnectAttempt ?: return
        reconnectAttempt = null
        runCatching {
            reports.enqueue("other", ReportText.safe(attempt.request.channelName, 120,
                DiagnosticRedactor.secrets(listOf(attempt.request.url))), "auto-reconnect: $outcome", "")
        }
    }

    fun cancelAutoReconnect(): Boolean {
        val attempt = reconnectAttempt ?: return false
        stop()
        mutableStatus.value = failed(attempt.error)
        return true
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
        /** OkHttp closes an idle pooled socket after five minutes. */
        const val POOL_IDLE_MS = 5 * 60_000L
        const val SLOW_START_MS = 3_000L
        const val RETRY_DELAY_MS = 1_500L
        const val MAX_RETRIES = 3
    }
}
