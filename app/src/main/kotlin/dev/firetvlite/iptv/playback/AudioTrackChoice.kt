package dev.firetvlite.iptv.playback

data class AudioTrackChoice(val group: Int, val index: Int, val language: String?, val label: String?,
    val codec: String?, val selected: Boolean) {
    val title: String get() = listOfNotNull(language?.takeIf { it.isNotBlank() }, label?.takeIf { it.isNotBlank() },
        codec?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ").ifBlank { "Ścieżka ${index + 1}" }
}

object AudioTrackSelection {
    fun valid(audio: Boolean, count: Int, index: Int, supported: Boolean) = audio && index in 0 until count && supported
}
