package dev.firetvlite.iptv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.FavoriteEntity
import dev.firetvlite.iptv.data.db.RecentEntity
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.playlist.PlaylistHttpHeaders
import dev.firetvlite.iptv.data.library.ZapContext
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.playback.PlaybackStatus
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.playback.PlaybackFrame
import dev.firetvlite.iptv.playback.PlaybackTime
import dev.firetvlite.iptv.playback.SeekPlan
import dev.firetvlite.iptv.playback.SeekTarget
import dev.firetvlite.iptv.data.epg.CurrentAndNext
import dev.firetvlite.iptv.playback.StreamRequest
import dev.firetvlite.iptv.playback.DeadChannels
import dev.firetvlite.iptv.playback.ZapMath
import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.ProgrammePlayback
import dev.firetvlite.iptv.playback.SeekDebouncer
import dev.firetvlite.iptv.playback.TimelineAction
import dev.firetvlite.iptv.playback.TimelineItem
import dev.firetvlite.iptv.playback.PreviousChannel
import dev.firetvlite.iptv.playback.StartupResume
import dev.firetvlite.iptv.playback.PlaybackSourcePolicy
import dev.firetvlite.iptv.playback.AudioTrackChoice
import dev.firetvlite.iptv.data.library.QueryZapContext
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Short hints shown over the picture. */
enum class PlayerMessage { SeekUnsupported, CatchupUnsupported }

/** Position in the list the user is zapping through (1-based) and its size. */
data class ZapPosition(val number: Int, val count: Int)

data class BrowserTarget(val group: GroupKey, val channel: ChannelEntity, val token: Int)

data class ChannelTimelineTarget(
    val channel: ChannelEntity,
    val zap: ZapContext,
    val index: Int,
    val group: GroupKey,
    val frame: PlaybackFrame,
    val liveWindowStart: Long?,
    val playing: Boolean,
    val fromOverlay: Boolean = false,
)

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
    val startupDelayed: StateFlow<Boolean> = playback.startupDelayed
    val providerWaiting: StateFlow<Boolean> = playback.providerWaiting

    private val mutableMode = MutableStateFlow(PlayerMode.Closed)
    val mode = mutableMode.asStateFlow()
    private val mutableBrowserTarget = MutableStateFlow<BrowserTarget?>(null)
    val browserTarget = mutableBrowserTarget.asStateFlow()
    private val mutableTimeline = MutableStateFlow<ChannelTimelineTarget?>(null)
    val timeline = mutableTimeline.asStateFlow()
    private var playingGroup: GroupKey = GroupKey.All
    private var targetToken = 0
    private var previewJob: Job? = null
    private var activeChannel: ChannelEntity? = null
    private var activeIndex = 0
    private val channelDao = container.database.channelDao()
    private val previous = PreviousChannel<ChannelEntity> { it.streamUrl }
    private var watchedSourceId: Long? = null
    private var startupPending = true
    private var startupJob: Job? = null

    private val mutableChannel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = mutableChannel.asStateFlow()
    private val mutableLiveChannelId = MutableStateFlow<Long?>(null)
    val liveChannelId: StateFlow<Long?> = mutableLiveChannelId.asStateFlow()

    private val mutablePosition = MutableStateFlow(ZapPosition(0, 0))
    val position: StateFlow<ZapPosition> = mutablePosition.asStateFlow()

    private var archiveStartMs: Long? = null
    private var fallbackStartMs = System.currentTimeMillis()
    private var pendingSeekMs: Long? = null
    private val seekDebouncer = SeekDebouncer(viewModelScope, ::seek)
    private val mutableFrame = MutableStateFlow(PlaybackFrame())
    val frame = mutableFrame.asStateFlow()
    val programme = combine(channel, frame) { selected, frame -> selected?.id to frame.watchedMs / 1_000 }
        .distinctUntilChanged()
        .flatMapLatest { (id, time) ->
            if (id == null) flowOf(CurrentAndNext(null, null)) else epg.observeCurrentAndNextAt(id, time * 1_000)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrentAndNext(null, null))

    init {
        viewModelScope.launch {
            sourceDao.observeActive().collect { source ->
                if (PlaybackSourcePolicy.shouldClose(mutableChannel.value?.sourceId, source?.id)) close()
            }
        }
        startupJob = viewModelScope.launch {
            val (sourceId, recent) = StartupResume.awaitRecent(
                sourceDao.observeActive().map { it?.id }.distinctUntilChanged(), channelDao::startupRecent)
            val last = recent.firstOrNull()
            val resume = StartupResume.allowed(true, last != null, mutableMode.value, startupPending)
            if (resume && last != null) {
                val index = channelDao.allIndex(sourceId, last.streamUrl) ?: return@launch
                if (startupPending && mutableMode.value == PlayerMode.Closed && sourceDao.active()?.id == sourceId) {
                    previous.seedRecent(recent)
                    watchedSourceId = sourceId
                    cancelPreview()
                    select(allChannels(sourceId), index, GroupKey.All, PlayerMode.Fullscreen, restoring = true)
                }
            }
            startupPending = false
        }
        viewModelScope.launch {
            status.collect { state ->
                val start = archiveStartMs
                val channel = activeChannel
                if (state == PlaybackStatus.Ended && start != null && channel != null && mutableMode.value.continuesPlayback) {
                    catchupJob?.cancel()
                    pendingSeekMs = null
                    when (val plan = PlaybackTime.archiveContinuation(updateFrame(), start)) {
                        is SeekPlan.Archive -> applyCatchup(channel, plan.timeMs, userInitiated = false)
                        else -> startLive(channel, userInitiated = false)
                    }
                }
            }
        }
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
        startupPending = false
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

    private fun select(zap: ZapContext, startIndex: Int, group: GroupKey, mode: PlayerMode, archiveTime: Long? = null,
        restoring: Boolean = false) {
        seekDebouncer.cancel()
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        pendingSeekMs = null
        openJob = viewModelScope.launch {
            val count = zap.count()
            if (count == 0) return@launch
            val index = startIndex.coerceIn(0, count - 1)
            val first = zap.at(index) ?: return@launch
            val source = sourceDao.active()
            if (source?.id != first.sourceId) return@launch
            userAgent = source.userAgent
            zapContext = zap
            zapCount = count
            zapIndex = index
            playingGroup = group
            val sameChannel = mutableMode.value != PlayerMode.Closed && mutableChannel.value?.streamUrl == first.streamUrl &&
                status.value != PlaybackStatus.Idle
            setMode(mode)
            show(first, archive = archiveTime != null)
            if (sameChannel) {
                activeChannel = first
                activeIndex = index
            }
            if (archiveTime != null) {
                activeChannel = first
                activeIndex = index
                applyCatchup(first, archiveTime, restartIfActive = true)
            } else if (restoring) startLive(first, userInitiated = false)
            else if (!sameChannel || archiveStartMs != null || status.value is PlaybackStatus.Failed) startLive(first)
            markWatched(first)
        }
    }

    private fun setMode(mode: PlayerMode) {
        mutableMode.value = mode
        playback.setPreview(mode == PlayerMode.Preview || mode == PlayerMode.Guide || mode == PlayerMode.Panel)
    }

    fun cancelStartupResume() { startupPending = false; startupJob?.cancel() }

    fun showPanel() {
        cancelPreview()
        settlePendingZap()
        closeTimeline()
        setMode(PlayerMode.Panel)
    }

    fun returnToFullscreen() {
        cancelPreview()
        closeTimeline()
        val channel = activeChannel
        if (channel != null && !playback.hasActiveStream) {
            val start = archiveStartMs
            if (start == null) startLive(channel) else applyCatchup(channel, start)
        }
        setMode(mutableMode.value.afterScreen(activeChannel != null))
    }

    fun openGuide(group: GroupKey = playingGroup, channel: ChannelEntity? = activeChannel) {
        cancelStartupResume()
        cancelPreview()
        settlePendingZap()
        closeTimeline()
        mutableBrowserTarget.value = channel?.let { BrowserTarget(group, it, ++targetToken) }
        setMode(PlayerMode.Guide)
    }

    private fun allChannels(sourceId: Long) = QueryZapContext(
        countQuery = { channelDao.count(sourceId) }, atQuery = { channelDao.allAt(sourceId, it) })

    fun previousChannel() {
        val channel = previous.previous ?: return
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val source = sourceDao.active() ?: return@launch
            if (source.id != channel.sourceId) return@launch
            val index = channelDao.allIndex(source.id, channel.streamUrl) ?: return@launch
            open(allChannels(source.id), index, GroupKey.All)
        }
    }

    fun audioTracks() = playback.audioTracks()
    fun selectAudioTrack(choice: AudioTrackChoice) = playback.selectAudioTrack(choice)
    fun isArchive() = archiveStartMs != null

    fun openOverlay() {
        if (mutableMode.value != PlayerMode.Fullscreen) return
        settlePendingZap()
        setMode(PlayerMode.Overlay)
        restoreChannel()
    }

    fun closeOverlay() {
        if (mutableMode.value == PlayerMode.Overlay) setMode(mutableMode.value.afterBack())
    }

    fun openTimeline(
        zap: ZapContext? = zapContext,
        index: Int = activeIndex,
        group: GroupKey = playingGroup,
        channel: ChannelEntity? = activeChannel,
    ) {
        if (!mutableMode.value.fullFrame || zap == null || channel == null) return
        cancelPreview()
        settlePendingZap()
        val playing = activeChannel?.streamUrl == channel.streamUrl
        val current = updateFrame()
        val frame = if (playing) current else PlaybackFrame(current.nowMs, current.nowMs)
        val windowStart = if (playing && archiveStartMs == null && frame.snapshot.seekable)
            frame.watchedMs - frame.snapshot.positionMs else null
        mutableTimeline.value = ChannelTimelineTarget(channel, zap, index, group, frame, windowStart, playing,
            fromOverlay = mutableMode.value == PlayerMode.Overlay)
        setMode(PlayerMode.Fullscreen)
    }

    fun closeTimeline() { mutableTimeline.value = null }

    fun dismissTimeline() {
        val target = mutableTimeline.value ?: return
        closeTimeline()
        if (target.fromOverlay) {
            mutableBrowserTarget.value = BrowserTarget(target.group, target.channel, ++targetToken)
            setMode(PlayerMode.Overlay)
        }
    }

    fun openLiveProgramme(zap: ZapContext, index: Int, group: GroupKey, channel: ChannelEntity, programme: ProgrammeEntity?) {
        closeTimeline()
        if (activeChannel?.streamUrl == channel.streamUrl && archiveStartMs == null) {
            val position = ProgrammePlayback.bufferedStart(programme, updateFrame(), sameLiveChannel = true,
                supportsCatchup = CatchupUrlBuilder.isSupported(channel))
            if (position != null) playback.seekTo(position) else playback.seekToLiveEdge()
        }
        open(zap, index, group)
    }

    fun playTimeline(item: TimelineItem) {
        val target = mutableTimeline.value ?: return
        val now = System.currentTimeMillis()
        when (ChannelTimeline.selection(item, now, target.channel.catchupDays, CatchupUrlBuilder.isSupported(target.channel))) {
            TimelineAction.None -> return
            TimelineAction.Archive -> {
                closeTimeline()
                openArchive(target.zap, target.index, target.group, item.programme!!.start)
            }
            TimelineAction.Live -> {
                openLiveProgramme(target.zap, target.index, target.group, target.channel,
                    item.programme)
            }
            TimelineAction.Past -> {
                val channel = target.channel
                if (CatchupUrlBuilder.isSupported(channel) && channel.catchupDays > 0) {
                    if (item.programme != null) {
                        closeTimeline()
                        open(target.zap, target.index, target.group)
                        return
                    }
                    val limit = now - channel.catchupDays.toLong() * ChannelTimeline.DAY
                    if (item.stop <= limit) return
                    closeTimeline()
                    openArchive(target.zap, target.index, target.group, maxOf(item.start, limit))
                } else if (activeChannel?.streamUrl == channel.streamUrl && archiveStartMs == null) {
                    val frame = updateFrame()
                    if (!frame.snapshot.seekable) { notify(PlayerMessage.SeekUnsupported); return }
                    closeTimeline()
                    seekAbsolute(maxOf(item.start, frame.watchedMs - frame.snapshot.positionMs))
                } else {
                    closeTimeline()
                    open(target.zap, target.index, target.group)
                }
            }
        }
    }

    private fun restoreChannel() {
        mutableChannel.value?.let { mutableBrowserTarget.value = BrowserTarget(playingGroup, it, ++targetToken) }
    }

    private fun settlePendingZap() {
        seekDebouncer.cancel()
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
        seekDebouncer.cancel()
        zapIndex = ZapMath.wrap(zapIndex, delta, zapCount)
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
        catchupJob?.cancel()
        pendingSeekMs = null
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            val next = zap.at(zapIndex) ?: return@launch
            show(next, archive = false) // the info bar shows the new name at once
            delay(ZAP_DEBOUNCE_MS)
            startLive(next)
            markWatched(next)
        }
    }

    fun close() {
        seekDebouncer.cancel()
        previous.clear()
        closeTimeline()
        cancelPreview()
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        messageJob?.cancel()
        playback.stop()
        setMode(PlayerMode.Closed)
        mutableBrowserTarget.value = null
        mutableChannel.value = null
        mutableLiveChannelId.value = null
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

    fun beginSeek() {
        seekDebouncer.hold()
        if (pendingSeekMs != null) catchupJob?.cancel()
    }

    fun finishSeek(deltaMs: Long) = seekDebouncer.release(deltaMs)

    fun seekTarget(frame: PlaybackFrame, deltaMs: Long): Long? {
        val channel = mutableChannel.value ?: return null
        return SeekTarget.time(frame, deltaMs, archiveStartMs, fallbackStartMs, channel.catchupDays,
            CatchupUrlBuilder.isSupported(channel), pendingSeekMs)
    }

    fun cancelSeek() {
        seekDebouncer.cancel()
        if (pendingSeekMs != null) {
            catchupJob?.cancel()
            pendingSeekMs = null
        }
    }

    fun seek(deltaMs: Long) {
        pendingSeekMs?.let { seekAbsolute(it + deltaMs); return }
        val channel = mutableChannel.value ?: return
        applySeekPlan(PlaybackTime.relativeSeekPlan(deltaMs, updateFrame(), archiveStartMs, fallbackStartMs,
            channel.catchupDays, CatchupUrlBuilder.isSupported(channel)), channel)
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
        applySeekPlan(plan, channel)
    }

    private fun applySeekPlan(plan: SeekPlan, channel: ChannelEntity) {
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

    private fun applyCatchup(channel: ChannelEntity, time: Long, restartIfActive: Boolean = false, userInitiated: Boolean = true) {
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
        mutableLiveChannelId.value = null
        val request = StreamRequest(url, userAgent, archive = true, channelName = channel.name,
            headers = PlaylistHttpHeaders.decode(channel.requestHeaders), channelKey = DeadChannels.key(channel.streamUrl),
            userInitiated = userInitiated)
        if (restartIfActive) playback.playArchiveFromStart(request) else playback.play(request)
        updateFrame()
    }

    private fun show(channel: ChannelEntity, archive: Boolean = archiveStartMs != null) {
        mutableLiveChannelId.value = channel.id.takeUnless { archive }
        mutableChannel.value = channel
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
    }

    private fun startLive(channel: ChannelEntity, userInitiated: Boolean = true) {
        activeChannel = channel
        activeIndex = zapIndex
        archiveStartMs = null
        fallbackStartMs = System.currentTimeMillis()
        mutableLiveChannelId.value = channel.id
        playback.play(StreamRequest(channel.streamUrl, userAgent, channelName = channel.name,
            headers = PlaylistHttpHeaders.decode(channel.requestHeaders), userInitiated = userInitiated))
        updateFrame()
    }

    private fun markWatched(channel: ChannelEntity) {
        if (!mutableMode.value.recordsWatch) return
        if (watchedSourceId != channel.sourceId) previous.clear()
        watchedSourceId = channel.sourceId
        previous.watched(channel)
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
