package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.film.FilmShowing
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ZapContext

internal suspend fun openFilmChannel(
    channel: ChannelEntity,
    indexOf: suspend (Long) -> Int?,
    context: suspend (GroupKey) -> ZapContext?,
    open: (ZapContext, Int, GroupKey) -> Unit,
) {
    val zap = context(GroupKey.All) ?: return
    val index = indexOf(channel.id) ?: return
    val target = zap.at(index) ?: return
    if (target.id == channel.id && target.sourceId == channel.sourceId && target.streamUrl == channel.streamUrl) {
        open(zap, index, GroupKey.All)
    }
}

internal suspend fun openArchivedFilm(
    film: FilmShowing,
    indexOf: suspend (Long) -> Int?,
    context: suspend (GroupKey) -> ZapContext?,
    openArchive: (ZapContext, Int, GroupKey, Long) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    if (!Films.archived(film.channel, film.programme, clock())) return
    openFilmChannel(film.channel, indexOf, context) { zap, index, group ->
        if (Films.archived(film.channel, film.programme, clock())) openArchive(zap, index, group, film.programme.start)
    }
}
