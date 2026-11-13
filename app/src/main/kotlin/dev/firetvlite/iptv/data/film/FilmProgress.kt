package dev.firetvlite.iptv.data.film

data class FilmProgress(val total: Int = 0, val checked: Int = 0, val rated: Int = 0, val missing: Int = 0) {
    val fraction: Float get() = if (total == 0) 0f else checked.toFloat() / total
}

internal fun filmProgress(showings: List<FilmShowing>, now: Long): List<FilmProgress> {
    val tabs = List(Films.TAB_COUNT) { linkedMapOf<String, FilmShowing>() }
    for (showing in showings) {
        val p = showing.programme
        if (!Films.candidate(p)) continue
        val tab = when {
            Films.archived(showing.channel, p, now) -> Films.ARCHIVE_TAB
            Films.inWindow(p, now, Films.LIVE_TAB) -> Films.LIVE_TAB
            Films.inWindow(p, now, Films.UPCOMING_TAB) -> Films.UPCOMING_TAB
            Films.inWindow(p, now, Films.FUTURE_TAB) -> Films.FUTURE_TAB
            else -> continue
        }
        tabs[tab].putIfAbsent(Films.key(p), showing)
    }
    return tabs.map { tab ->
        val rows = tab.values
        FilmProgress(rows.size, rows.count { it.rating != null },
            rows.count { it.rating?.imdbRating?.isFinite() == true },
            rows.count { it.rating != null && it.rating.imdbId == null })
    }
}
