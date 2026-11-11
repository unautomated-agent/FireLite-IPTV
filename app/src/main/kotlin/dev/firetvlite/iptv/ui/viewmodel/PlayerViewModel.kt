package dev.firetvlite.iptv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FavoriteEntity
import dev.firetvlite.iptv.data.db.RecentEntity
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.library.ZapContext
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.playback.PlaybackStatus
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.playback.PlaybackFrame
import dev.firetvlite.iptv.playback.PlaybackTime
import dev.firetvlite.iptv.playback.SeekPlan
import dev.firetvlite.iptv.data.epg.CurrentAndNext
import dev.firetvlite.iptv.playback.StreamRequest
import dev.firetvlite.iptv.playback.ZapMath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Short hints shown over the picture. */
enum class PlayerMessage { SeekUnsupported, CatchupUnsupported }

/** Position in the list the user is zapping through (1-based) and its size. */
data class ZapPosition(val number: Int, val count: Int)

data class BrowserTarget(val group: GroupKey, val channel: ChannelEntity, val token: Int)

/**
 * Drives preview, fullscreen and overlay: channel selection, favourites and archive playback.
 * All streams go through [dev.firetvlite.iptv.playback.PlaybackController], which keeps to one connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FireLiteApplication).container
    private val playback = container.playback
    private val userDao = container.database.userDao()
    private val sourceDao = container.database.sourceDao()
    private val epg = container.epg

    val player: StateFlow<ExoPlayer?> = playback.player
    val status: StateFlow<PlaybackStatus> = playback.status

    private val mutableMode = MutableStateFlow(PlayerMode.Closed)
    val mode = mutableMode.asStateFlow()
    private val mutableBrowserTarget = MutableStateFlow<BrowserTarget?>(null)
    val browserTarget = mutableBrowserTarget.asStateFlow()
    private var playingGroup: GroupKey = GroupKey.All
    private var targetToken = 0
    private var previewJob: Job? = null
    private var activeChannel: ChannelEntity? = null
    private var activeIndex = 0

    private val mutableChannel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = mutableChannel.asStateFlow()

    private val mutablePosition = MutableStateFlow(ZapPosition(0, 0))
    val position: StateFlow<ZapPosition> = mutablePosition.asStateFlow()

    private var archiveStartMs: Long? = null
    private var fallbackStartMs = System.currentTimeMillis()
    private var pendingSeekMs: Long? = null
    private val mutableFrame = MutableStateFlow(PlaybackFrame())
    val frame = mutableFrame.asStateFlow()
    val programme = combine(channel, frame) { selected, frame -> selected?.id to frame.watchedMs / 1_000 }
        .distinctUntilChanged()
        .flatMapLatest { (id, time) ->
            if (id == null) flowOf(CurrentAndNext(null, null)) else epg.observeCurrentAndNextAt(id, time * 1_000)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrentAndNext(null, null))

    init {
        viewModelScope.launch {
            mode.collectLatest { mode ->
                if (mode == PlayerMode.Closed) mutableFrame.value = PlaybackFrame()
                else while (true) { updateFrame(); delay(500) }
            }
        }
    }

    private val mutableMessage = MutableStateFlow<PlayerMessage?>(null)
    val message: StateFlow<PlayerMessage?> = mutableMessage.asStateFlow()

    val isFavorite: StateFlow<Boolean> = mutableChannel
        .flatMapLatest { channel ->
            if (channel == null) flowOf(false) else userDao.observeIsFavorite(channel.streamUrl)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var zapContext: ZapContext? = null
    private var zapCount = 0
    private var zapIndex = 0
    private var userAgent = LibraryRepository.DEFAULT_USER_AGENT
    private var openJob: Job? = null
    private var zapJob: Job? = null
    private var catchupJob: Job? = null
    private var messageJob: Job? = null

    /** Opens the player on item [startIndex] of [zap] and starts playing it. */
    fun open(zap: ZapContext, startIndex: Int, group: GroupKey) {
        cancelPreview()
        select(zap, startIndex, group, PlayerMode.Fullscreen)
    }

    fun preview(zap: ZapContext, startIndex: Int, group: GroupKey) {
        cancelPreview()
        if (!mutableMode.value.acceptsPreview) return
        previewJob = viewModelScope.launch {
            delay(800)
            select(zap, startIndex, group, PlayerMode.Preview)
        }
    }

    fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    fun openArchive(zap: ZapContext, startIndex: Int, group: GroupKey, time: Long) {
        cancelPreview()
        select(zap, startIndex, group, PlayerMode.Fullscreen, time)
    }

    private fun select(zap: ZapContext, startIndex: Int, group: GroupKey, mode: PlayerMode, archiveTime: Long? = null) {
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        pendingSeekMs = null
        openJob = viewModelScope.launch {
            val count = zap.count()
            if (count == 0) return@launch
            val index = startIndex.coerceIn(0, count - 1)
            val first = zap.at(index) ?: return@launch
            userAgent = sourceDao.active()?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT
            zapContext = zap
            zapCount = count
            zapIndex = index
            playingGroup = group
            val sameChannel = mutableMode.value != PlayerMode.Closed && mutableChannel.value?.streamUrl == first.streamUrl
            setMode(mode)
            show(first)
            if (sameChannel) {
                activeChannel = first
                activeIndex = index
            }
            if (archiveTime != null) {
                activeChannel = first
                activeIndex = index
                applyCatchup(first, archiveTime)
            } else if (!sameChannel || archiveStartMs != null || status.value is PlaybackStatus.Failed) startLive(first)
        }
    }

    private fun setMode(mode: PlayerMode) {
        mutableMode.value = mode
        playback.setPreview(mode == PlayerMode.Preview)
    }

    fun backToPreview() {
        if (mutableChannel.value == null) return
        cancelPreview()
        settlePendingZap()
        setMode(mutableMode.value.afterBack())
        if (archiveStartMs != null) activeChannel?.let(::startLive)
        restoreChannel()
    }

    fun openOverlay() {
        if (mutableMode.value != PlayerMode.Fullscreen) return
        settlePendingZap()
        setMode(PlayerMode.Overlay)
        restoreChannel()
    }

    fun closeOverlay() {
        if (mutableMode.value == PlayerMode.Overlay) setMode(mutableMode.value.afterBack())
    }

    private fun restoreChannel() {
        mutableChannel.value?.let { mutableBrowserTarget.value = BrowserTarget(playingGroup, it, ++targetToken) }
    }

    private fun settlePendingZap() {
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        pendingSeekMs = null
        activeChannel?.let {
            zapIndex = activeIndex
            show(it)
        }
    }

    /** Channel up (-1) / down (+1). Rapid presses are merged: only the channel the user stops on connects. */
    fun zap(delta: Int) {
        if (mutableMode.value != PlayerMode.Fullscreen) return
        val zap = zapContext ?: return
        if (zapCount == 0) return
        zapIndex = ZapMath.wrap(zapIndex, delta, zapCount)
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
        catchupJob?.cancel()
        pendingSeekMs = null
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            val next = zap.at(zapIndex) ?: return@launch
            show(next) // the info bar shows the new name at once
            delay(ZAP_DEBOUNCE_MS)
            startLive(next)
        }
    }

    fun close() {
        cancelPreview()
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        messageJob?.cancel()
        playback.stop()
        setMode(PlayerMode.Closed)
        mutableBrowserTarget.value = null
        mutableChannel.value = null
        activeChannel = null
        archiveStartMs = null
        pendingSeekMs = null
        mutableMessage.value = null
        mutablePosition.value = ZapPosition(0, 0)
        zapCount = 0
        zapContext = null
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    fun toggleFavorite() {
        val channel = mutableChannel.value ?: return
        viewModelScope.launch {
            if (userDao.isFavorite(channel.streamUrl)) {
                userDao.removeFavorite(channel.streamUrl)
            } else {
                userDao.addFavorite(FavoriteEntity(channel.streamUrl, System.currentTimeMillis()))
            }
        }
    }

    fun togglePause() = playback.togglePause()

    fun pause() = playback.pause()

    fun resume() = playback.resume()

    private fun updateFrame(): PlaybackFrame {
        val now = System.currentTimeMillis()
        val snapshot = playback.snapshot()
        return PlaybackFrame(now, PlaybackTime.watched(now, snapshot, archiveStartMs, fallbackStartMs), snapshot)
            .also { mutableFrame.value = it }
    }

    fun seek(deltaMs: Long) {
        seekAbsolute((pendingSeekMs ?: updateFrame().watchedMs) + deltaMs)
    }

    fun jump(forward: Boolean) = seek(if (forward) BIG_SEEK_MS else -BIG_SEEK_MS)

    fun jumpToProgrammeStart() {
        val channel = mutableChannel.value ?: return
        if (!CatchupUrlBuilder.isSupported(channel)) {
            notify(PlayerMessage.SeekUnsupported)
            return
        }
        val watched = updateFrame().watchedMs
        catchupJob?.cancel()
        pendingSeekMs = null
        catchupJob = viewModelScope.launch {
            val current = epg.programmeAt(channel.id, watched)
            catchupJob = null
            if (current == null) notify(PlayerMessage.SeekUnsupported) else seekAbsolute(current.start)
        }
    }

    private fun seekAbsolute(time: Long) {
        val channel = mutableChannel.value ?: return
        val frame = updateFrame()
        val plan = PlaybackTime.seekPlan(time, frame.nowMs, frame.snapshot, archiveStartMs, fallbackStartMs,
            channel.catchupDays, CatchupUrlBuilder.isSupported(channel))
        catchupJob?.cancel()
        pendingSeekMs = null
        when (plan) {
            is SeekPlan.InWindow -> { playback.seekTo(plan.positionMs); updateFrame() }
            SeekPlan.Live -> {
                if (archiveStartMs == null) playback.seekToLiveEdge()
                else scheduleArchive(channel, null)
            }
            is SeekPlan.Archive -> scheduleArchive(channel, plan.timeMs)
            SeekPlan.Unavailable -> notify(PlayerMessage.SeekUnsupported)
        }
    }

    private fun scheduleArchive(channel: ChannelEntity, time: Long?) {
        pendingSeekMs = time ?: System.currentTimeMillis()
        catchupJob = viewModelScope.launch {
            delay(CATCHUP_DEBOUNCE_MS)
            pendingSeekMs = null
            if (time == null) startLive(channel) else applyCatchup(channel, time)
        }
    }

    private fun applyCatchup(channel: ChannelEntity, time: Long) {
        val now = System.currentTimeMillis() / 1000
        val start = time / 1000
        val url = CatchupUrlBuilder.build(
            channel = channel,
            startEpochSec = start,
            nowEpochSec = now,
            durationSec = (now - start).coerceAtLeast(1),
        )
        if (url == null) {
            notify(PlayerMessage.CatchupUnsupported)
            return
        }
        archiveStartMs = start * 1000
        playback.play(StreamRequest(url, userAgent))
        updateFrame()
    }

    private fun show(channel: ChannelEntity) {
        mutableChannel.value = channel
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
    }

    private fun startLive(channel: ChannelEntity) {
        activeChannel = channel
        activeIndex = zapIndex
        archiveStartMs = null
        fallbackStartMs = System.currentTimeMillis()
        playback.play(StreamRequest(channel.streamUrl, userAgent))
        updateFrame()
        viewModelScope.launch {
            userDao.markWatched(RecentEntity(channel.streamUrl, System.currentTimeMillis()))
            userDao.trimRecent()
        }
    }

    private fun notify(message: PlayerMessage) {
        mutableMessage.value = message
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            mutableMessage.value = null
        }
    }

    private companion object {
        const val ZAP_DEBOUNCE_MS = 400L
        const val CATCHUP_DEBOUNCE_MS = 700L
        const val BIG_SEEK_MS = 30_000L
        const val MESSAGE_MS = 2_500L
    }
}
