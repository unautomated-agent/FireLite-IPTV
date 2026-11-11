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
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.playback.PlaybackSnapshot
import dev.firetvlite.iptv.playback.PlaybackStatus
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Short hints shown over the picture. */
enum class PlayerMessage { SeekUnsupported, CatchupUnsupported }

/** Position in the list the user is zapping through (1-based) and its size. */
data class ZapPosition(val number: Int, val count: Int)

/**
 * Drives the full-screen player: channel up/down, favourites, jumping back and forth.
 * All streams go through [dev.firetvlite.iptv.playback.PlaybackController], which keeps to one connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as FireLiteApplication).container
    private val playback = container.playback
    private val userDao = container.database.userDao()
    private val sourceDao = container.database.sourceDao()

    val player: StateFlow<ExoPlayer?> = playback.player
    val status: StateFlow<PlaybackStatus> = playback.status

    private val mutableOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = mutableOpen.asStateFlow()

    private val mutableChannel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = mutableChannel.asStateFlow()

    private val mutablePosition = MutableStateFlow(ZapPosition(0, 0))
    val position: StateFlow<ZapPosition> = mutablePosition.asStateFlow()

    /** Minutes the user went back with the archive, 0 while watching live. */
    private val mutableCatchupOffset = MutableStateFlow(0)
    val catchupOffsetMinutes: StateFlow<Int> = mutableCatchupOffset.asStateFlow()

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
    fun open(zap: ZapContext, startIndex: Int) {
        if (mutableOpen.value || openJob?.isActive == true) return
        openJob = viewModelScope.launch {
            val count = zap.count()
            if (count == 0) return@launch
            val index = startIndex.coerceIn(0, count - 1)
            val first = zap.at(index) ?: return@launch
            userAgent = sourceDao.first()?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT
            zapContext = zap
            zapCount = count
            zapIndex = index
            mutableOpen.value = true
            show(first)
            startLive(first)
        }
    }

    /** Channel up (-1) / down (+1). Rapid presses are merged: only the channel the user stops on connects. */
    fun zap(delta: Int) {
        val zap = zapContext ?: return
        if (zapCount == 0) return
        zapIndex = ZapMath.wrap(zapIndex, delta, zapCount)
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
        catchupJob?.cancel()
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            val next = zap.at(zapIndex) ?: return@launch
            show(next) // the info bar shows the new name at once
            delay(ZAP_DEBOUNCE_MS)
            startLive(next)
        }
    }

    fun close() {
        openJob?.cancel()
        zapJob?.cancel()
        catchupJob?.cancel()
        messageJob?.cancel()
        playback.stop()
        mutableOpen.value = false
        mutableChannel.value = null
        mutableCatchupOffset.value = 0
        mutableMessage.value = null
        mutablePosition.value = ZapPosition(0, 0)
        zapCount = 0
        zapContext = null
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

    fun snapshot(): PlaybackSnapshot = playback.snapshot()

    /** Jumps [deltaMs] inside a seekable stream (arrow keys). Does nothing otherwise. */
    fun seek(deltaMs: Long) {
        playback.seekBy(deltaMs)
    }

    /**
     * Rewind / fast-forward keys. A seekable stream jumps by 30 seconds. Otherwise, if the channel offers an
     * archive (catch-up), the archive is entered in 5 minute steps, and fast-forwarding returns to live.
     */
    fun jump(forward: Boolean) {
        val channel = mutableChannel.value ?: return
        if (playback.snapshot().seekable) {
            playback.seekBy(if (forward) BIG_SEEK_MS else -BIG_SEEK_MS)
            return
        }
        if (!CatchupUrlBuilder.isSupported(channel)) {
            notify(PlayerMessage.SeekUnsupported)
            return
        }
        val maxMinutes = channel.catchupDays * 24 * 60
        val step = if (forward) -CATCHUP_STEP_MINUTES else CATCHUP_STEP_MINUTES
        val offset = (mutableCatchupOffset.value + step).coerceIn(0, maxMinutes)
        mutableCatchupOffset.value = offset
        catchupJob?.cancel()
        catchupJob = viewModelScope.launch {
            delay(CATCHUP_DEBOUNCE_MS)
            applyCatchup(channel, offset)
        }
    }

    private fun applyCatchup(channel: ChannelEntity, offsetMinutes: Int) {
        if (offsetMinutes == 0) {
            playback.play(StreamRequest(channel.streamUrl, userAgent))
            return
        }
        val now = System.currentTimeMillis() / 1000
        val seconds = offsetMinutes * 60L
        val url = CatchupUrlBuilder.build(
            channel = channel,
            startEpochSec = now - seconds,
            nowEpochSec = now,
            durationSec = seconds,
        )
        if (url == null) {
            mutableCatchupOffset.value = 0
            notify(PlayerMessage.CatchupUnsupported)
            return
        }
        playback.play(StreamRequest(url, userAgent))
    }

    private fun show(channel: ChannelEntity) {
        mutableChannel.value = channel
        mutablePosition.value = ZapPosition(zapIndex + 1, zapCount)
    }

    private fun startLive(channel: ChannelEntity) {
        mutableCatchupOffset.value = 0
        playback.play(StreamRequest(channel.streamUrl, userAgent))
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
        const val CATCHUP_STEP_MINUTES = 5
        const val BIG_SEEK_MS = 30_000L
        const val MESSAGE_MS = 2_500L
    }
}
