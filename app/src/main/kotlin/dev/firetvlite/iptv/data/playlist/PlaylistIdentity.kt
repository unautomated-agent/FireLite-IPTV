package dev.firetvlite.iptv.data.playlist

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ChannelIdentity

internal object PlaylistIdentity {
    fun retain(channels: List<ChannelEntity>, previous: List<ChannelIdentity>): List<ChannelEntity> {
        val ids = previous.groupBy { it.streamUrl }.mapValues { (_, rows) -> ArrayDeque(rows.map { it.id }) }
        return channels.map { it.copy(id = ids[it.streamUrl]?.removeFirstOrNull() ?: 0) }
    }
}
