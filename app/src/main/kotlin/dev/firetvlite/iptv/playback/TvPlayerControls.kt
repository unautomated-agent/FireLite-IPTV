package dev.firetvlite.iptv.playback

enum class BannerState { Hidden, Compact, Expanded }
enum class TvKey { Up, Down, Left, Right, Rewind, FastForward, PlayPause, Ok, LongOk, Menu, LongMenu, Back }
enum class TvAction(val seekMs: Long? = null) {
    None, PreviousChannel, Back10(-10_000), Forward10(10_000), Back60(-60_000), Forward60(60_000),
    TogglePause, Channels, Guide, History, QuickMenu, ScrollUp, ScrollDown
}

data class TvPlayerControls(val banner: BannerState = BannerState.Hidden, val deadline: Long = 0) {
    fun touch(now: Long) = if (banner == BannerState.Hidden) this else copy(deadline = now + HIDE_MS)
    fun expire(now: Long) = if (banner != BannerState.Hidden && now >= deadline) TvPlayerControls() else this

    fun key(key: TvKey, now: Long): Pair<TvPlayerControls, TvAction> {
        val action = when (key) {
            TvKey.Up -> if (banner == BannerState.Expanded) TvAction.ScrollUp else TvAction.None
            TvKey.Down -> when (banner) {
                BannerState.Hidden -> TvAction.PreviousChannel
                BannerState.Compact -> TvAction.None
                BannerState.Expanded -> TvAction.ScrollDown
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
            TvKey.Up -> copy(banner = if (banner == BannerState.Hidden) BannerState.Compact else BannerState.Expanded)
            TvKey.Down -> if (banner == BannerState.Compact) TvPlayerControls() else this
            TvKey.Back, TvKey.Ok, TvKey.LongOk, TvKey.Menu, TvKey.LongMenu -> TvPlayerControls()
            else -> this
        }
        return next.touch(now) to action
    }

    companion object { const val HIDE_MS = 8_000L }
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
        newestFirst.take(2).asReversed().forEach(::watched)
    }
}

object StartupResume {
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
