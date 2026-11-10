package dev.firetvlite.iptv.data.library

import dev.firetvlite.iptv.data.db.ChannelEntity

/** Which list the user is browsing, so channel up/down moves inside that list. */
sealed interface GroupKey {
    /** Stable key for lazy lists. */
    val id: String

    data object Favorites : GroupKey {
        override val id = "favorites"
    }

    data object Recent : GroupKey {
        override val id = "recent"
    }

    data object All : GroupKey {
        override val id = "all"
    }

    data class Named(val name: String) : GroupKey {
        override val id = "group:$name"
    }
}

/** An ordered list of channels that can be walked through one by one. */
interface ZapContext {
    suspend fun count(): Int

    suspend fun at(index: Int): ChannelEntity?
}

/** A list that is read from the database on demand (big lists such as "all channels"). */
class QueryZapContext(
    private val countQuery: suspend () -> Int,
    private val atQuery: suspend (Int) -> ChannelEntity?,
) : ZapContext {
    override suspend fun count(): Int = countQuery()

    override suspend fun at(index: Int): ChannelEntity? = atQuery(index)
}

/**
 * A fixed copy of a short list. Favourites and "recently watched" change while zapping (a watched channel
 * moves to the top), so they are frozen when playback starts.
 */
class ListZapContext(private val items: List<ChannelEntity>) : ZapContext {
    override suspend fun count(): Int = items.size

    override suspend fun at(index: Int): ChannelEntity? = items.getOrNull(index)
}
