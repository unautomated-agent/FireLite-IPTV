package dev.firetvlite.iptv.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

enum class BannerState { Hidden, Compact, Expanded }
enum class TvKey { Up, Down, Left, Right, Rewind, FastForward, PlayPause, Ok, LongOk, Menu, LongMenu, Back }
enum class TvAction(val seekMs: Long? = null) {
    None, PreviousChannel, Back10(-10_000), Forward10(10_000), Back60(-60_000), Forward60(60_000),
    TogglePause, Channels, Guide, History, QuickMenu, ScrollUp, ScrollDown
}

data class TvPlayerControls(val banner: BannerState = BannerState.Hidden, val deadline: Long = 0,
    val hideMs: Long = HIDE_MS) {
    fun touch(now: Long) = when (banner) {
        BannerState.Hidden -> this
        BannerState.Compact -> copy(deadline = now + hideMs)
        BannerState.Expanded -> copy(deadline = 0)
    }
    fun expire(now: Long) = if (banner == BannerState.Compact && now >= deadline) TvPlayerControls() else this

    fun key(key: TvKey, now: Long, panelOpen: Boolean = false, canScrollDown: Boolean = true,
        repeat: Boolean = false): Pair<TvPlayerControls, TvAction> {
        if (panelOpen) return TvPlayerControls() to TvAction.None
        val action = when (key) {
            TvKey.Up -> if (banner == BannerState.Expanded) TvAction.ScrollUp else TvAction.None
            TvKey.Down -> when (banner) {
                BannerState.Hidden -> TvAction.PreviousChannel
                BannerState.Compact -> TvAction.None
                BannerState.Expanded -> if (canScrollDown) TvAction.ScrollDown else TvAction.None
            }
            TvKey.Left -> TvAction.Back10
            TvKey.Right -> TvAction.Forward10
            TvKey.Rewind -> TvAction.Back60
            TvKey.FastForward -> TvAction.Forward60
            TvKey.PlayPause -> TvAction.TogglePause
            TvKey.Ok -> TvAction.Channels
            TvKey.LongOk -> TvAction.Guide
            TvKey.Menu -> TvAction.History
            TvKey.LongMenu -> TvAction.QuickMenu
            TvKey.Back -> TvAction.None
        }
        val next = when (key) {
            TvKey.Up -> copy(banner = if (banner == BannerState.Hidden) BannerState.Compact else BannerState.Expanded,
                hideMs = HIDE_MS)
            TvKey.Left, TvKey.Right, TvKey.Rewind, TvKey.FastForward ->
                copy(banner = if (banner == BannerState.Hidden) BannerState.Compact else banner, hideMs = SEEK_HIDE_MS)
            TvKey.Down -> if (banner == BannerState.Compact ||
                banner == BannerState.Expanded && !canScrollDown && !repeat) TvPlayerControls() else this
            TvKey.Back, TvKey.Ok, TvKey.LongOk, TvKey.Menu, TvKey.LongMenu -> TvPlayerControls()
            else -> this
        }
        return next.touch(now) to action
    }

    companion object {
        const val HIDE_MS = 8_000L
        const val SEEK_HIDE_MS = 4_000L
    }
}

class PreviousChannel<T>(private val identity: (T) -> String) {
    private var current: T? = null
    var previous: T? = null
        private set
    fun watched(channel: T) {
        if (current?.let(identity) != identity(channel)) previous = current
        current = channel
    }
    fun clear() { current = null; previous = null }
    fun seedRecent(newestFirst: List<T>) {
        clear()
        newestFirst.distinctBy(identity).take(2).asReversed().forEach(::watched)
    }
}

object StartupResume {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> awaitRecent(sources: Flow<Long?>, recent: (Long) -> Flow<List<T>>): Pair<Long, List<T>> =
        sources.flatMapLatest { id ->
            if (id == null) flowOf<Pair<Long, List<T>>?>(null) else recent(id).map { id to it }
        }.filterNotNull().first { it.second.isNotEmpty() }

    fun allowed(hasSource: Boolean, hasChannel: Boolean, mode: PlayerMode, pending: Boolean) =
        hasSource && hasChannel && mode == PlayerMode.Closed && pending
}

object CategorySwitch {
    fun <T> next(groups: List<T>, current: T, delta: Int): T? {
        if (groups.isEmpty()) return null
        val index = groups.indexOf(current).coerceAtLeast(0)
        return groups[Math.floorMod(index + delta, groups.size)]
    }
}
