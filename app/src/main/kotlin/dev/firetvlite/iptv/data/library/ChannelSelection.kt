package dev.firetvlite.iptv.data.library

import dev.firetvlite.iptv.data.db.ChannelEntity

internal data class ChannelSelection(val count: Int, val index: Int, val channel: ChannelEntity) {
    companion object {
        suspend fun resolve(expected: ChannelEntity, zap: ZapContext,
            indexOf: suspend () -> Int?): ChannelSelection? {
            val index = indexOf() ?: return null
            val channel = zap.at(index) ?: return null
            if (channel.sourceId != expected.sourceId || channel.id != expected.id ||
                channel.streamUrl != expected.streamUrl) return null
            val count = zap.count()
            if (index !in 0 until count) return null
            return ChannelSelection(count, index, channel)
        }
    }
}
