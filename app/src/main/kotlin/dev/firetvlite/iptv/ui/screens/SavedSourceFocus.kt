package dev.firetvlite.iptv.ui.screens

internal sealed interface SavedSourceFocusTarget {
    data class Row(val id: Long) : SavedSourceFocusTarget
    data object Add : SavedSourceFocusTarget
}

internal sealed interface SavedSourceFocusRequest {
    data class Activate(val id: Long) : SavedSourceFocusRequest
    data class Remove(val id: Long, val neighbours: List<Long>) : SavedSourceFocusRequest
    data class Return(val id: Long) : SavedSourceFocusRequest

    fun target(ids: List<Long>, activeId: Long?): SavedSourceFocusTarget? = when (this) {
        is Activate -> if (activeId == id && ids.firstOrNull() == id) SavedSourceFocusTarget.Row(id) else null
        is Remove -> if (id in ids) null else {
            val next = neighbours.firstOrNull { it in ids } ?: ids.firstOrNull()
            next?.let { SavedSourceFocusTarget.Row(it) } ?: SavedSourceFocusTarget.Add
        }
        is Return -> (id.takeIf { it in ids } ?: ids.firstOrNull())
            ?.let { SavedSourceFocusTarget.Row(it) } ?: SavedSourceFocusTarget.Add
    }
}
