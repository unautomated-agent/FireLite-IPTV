package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey

internal data class CategoryFocusRequest(val group: GroupKey, val generation: Long, val target: ChannelFocusTarget?)

internal class CategoryFocusMemory {
    private val targets = mutableMapOf<GroupKey, ChannelFocusTarget>()
    private var pending: CategoryFocusRequest? = null
    var generation = 0L
        private set

    fun target(group: GroupKey): ChannelFocusTarget? = targets[group]
    fun selection(current: GroupKey): GroupKey = pending?.group ?: current

    fun begin(group: GroupKey): CategoryFocusRequest {
        return CategoryFocusRequest(group, ++generation, targets[group]).also { pending = it }
    }

    fun isCurrent(request: CategoryFocusRequest): Boolean = pending == request

    fun record(group: GroupKey, generation: Long, target: ChannelFocusTarget): Boolean {
        if (generation != this.generation || pending != null) return false
        targets[group] = target
        return true
    }

    fun complete(request: CategoryFocusRequest, target: ChannelFocusTarget? = null): Boolean {
        if (!isCurrent(request)) return false
        if (target != null) targets[request.group] = target
        pending = null
        return true
    }
}
