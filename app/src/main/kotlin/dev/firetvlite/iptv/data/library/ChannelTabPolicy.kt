package dev.firetvlite.iptv.data.library

object ChannelTabPolicy {
    fun canSelectGroup(restoreToken: Int?, handledToken: Int?): Boolean =
        restoreToken == null || restoreToken == handledToken

    fun homeSection(sections: List<Pair<GroupKey, List<String>>>, group: GroupKey?, url: String?): Int =
        sections.indexOfFirst { it.first == group && url in it.second }.takeIf { it >= 0 }
            ?: sections.indexOfFirst { url in it.second }.takeIf { it >= 0 }
            ?: sections.indexOfFirst { it.second.isNotEmpty() }
}
