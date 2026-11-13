package dev.firetvlite.iptv.data.film

data class FilmCatalogue(val sourceId: Long? = null, val loaded: Boolean = false,
    val hasEpg: Boolean = false, val films: List<FilmShowing> = emptyList(),
    val progress: List<FilmProgress> = List(Films.TAB_COUNT) { FilmProgress() })

enum class RankingStatus { Loading, MissingEpg, Empty, Ready }

fun rankingStatus(loaded: Boolean, epgRunning: Boolean, hasEpg: Boolean,
    enriching: Boolean, hasRows: Boolean): RankingStatus = when {
    hasRows -> RankingStatus.Ready
    !loaded || epgRunning || enriching -> RankingStatus.Loading
    !hasEpg -> RankingStatus.MissingEpg
    !hasRows -> RankingStatus.Empty
    else -> RankingStatus.Ready
}
