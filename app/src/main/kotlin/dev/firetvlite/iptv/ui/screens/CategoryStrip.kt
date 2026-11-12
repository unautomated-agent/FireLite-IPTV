package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.playback.CategorySwitch

internal fun categoryStrip(groups: List<GroupKey>, current: GroupKey): List<GroupKey> =
    if (groups.size < 2 || current !in groups) listOf(current)
    else listOfNotNull(CategorySwitch.next(groups, current, -1), current, CategorySwitch.next(groups, current, 1))
