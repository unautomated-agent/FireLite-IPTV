package dev.firetvlite.iptv.data.library

object ChannelTabPolicy {
    fun canSelectGroup(restoreToken: Int?, handledToken: Int?): Boolean =
        restoreToken == null || restoreToken == handledToken
}
