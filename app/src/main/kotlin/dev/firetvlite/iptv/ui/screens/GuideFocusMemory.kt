package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey

internal enum class GuideFocusPane { Groups, Grid }
internal data class GuideCellFocus(val windowStart: Long, val time: Long, val programme: Boolean)

internal class GuideFocusMemory {
    private val cells = mutableMapOf<GroupKey, GuideCellFocus>()

    fun save(group: GroupKey, cell: GuideCellFocus) { cells[group] = cell }

    fun cell(group: GroupKey, now: Long): GuideCellFocus = cells[group]
        ?: GuideCellFocus(now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2), now, true)

    companion object {
        fun destination(forward: Boolean, repeatCount: Int): GuideFocusPane? = when {
            repeatCount != 0 -> null
            forward -> GuideFocusPane.Grid
            else -> GuideFocusPane.Groups
        }
    }
}
