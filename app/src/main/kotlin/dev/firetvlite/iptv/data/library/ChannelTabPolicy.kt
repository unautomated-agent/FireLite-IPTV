package dev.firetvlite.iptv.data.library

object ChannelTabPolicy {
    fun collectionFocusIndex(count: Int, removedIndex: Int?, restoredIndex: Int?, initialized: Boolean,
        preferHeader: Boolean): Int? = when {
        initialized && removedIndex == null -> null
        count == 0 -> -1
        removedIndex != null -> removedIndex.coerceIn(0, count - 1)
        preferHeader -> -1
        else -> (restoredIndex ?: 0).coerceIn(0, count - 1)
    }

    fun canSelectGroup(restoreToken: Int?, handledToken: Int?): Boolean =
        restoreToken == null || restoreToken == handledToken

    fun homeSection(sections: List<Pair<GroupKey, List<String>>>, group: GroupKey?, url: String?): Int =
        sections.indexOfFirst { it.first == group && url in it.second }.takeIf { it >= 0 }
            ?: sections.indexOfFirst { url in it.second }.takeIf { it >= 0 }
            ?: sections.indexOfFirst { it.second.isNotEmpty() }
}
