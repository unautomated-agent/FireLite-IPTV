package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import java.util.Locale

data class AudioTrackChoice(val group: Int, val index: Int, val language: String?, val label: String?,
    val codec: String?, val selected: Boolean, val roleFlags: Int = 0, val selectionFlags: Int = 0,
    val channelCount: Int = -1) {
    private val languageInfo get() = when (language?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')) {
        "pol", "pl" -> "PL" to "Polski"
        "eng", "en" -> "ENG" to "Angielski"
        "ger", "deu", "de" -> "DE" to "Niemiecki"
        "fre", "fra", "fr" -> "FR" to "Francuski"
        "rus", "ru" -> "RU" to "Rosyjski"
        "ukr", "uk" -> "UA" to "Ukraiński"
        "spa", "es" -> "ES" to "Hiszpański"
        "ita", "it" -> "IT" to "Włoski"
        else -> null
    }
    private val fallback get() = label?.takeIf { it.isNotBlank() } ?: "Ścieżka ${index + 1}"
    val languageChip: String get() = languageInfo?.first ?: language?.takeIf {
        it.isNotBlank() && !it.equals("und", true) && !it.equals("qad", true)
    }?.uppercase(Locale.ROOT) ?: fallback
    val readableName: String get() = languageInfo?.second ?: fallback
    val audioDescription: Boolean get() = roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 ||
        Regex("(?iu)(?:^|[^a-z])ad(?:$|[^a-z])|audiodeskrypc|audio.?description|qad")
            .containsMatchIn(label.orEmpty() + " " + language.orEmpty())
    val hearingImpaired: Boolean get() = roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 ||
        Regex("(?iu)niesłysz|\\bsdh\\b|hearing").containsMatchIn(label.orEmpty())
    val details: String get() = listOfNotNull(when (channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }, codec?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val title: String get() = listOfNotNull(language?.takeIf { it.isNotBlank() }, label?.takeIf { it.isNotBlank() },
        codec?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ").ifBlank { "Ścieżka ${index + 1}" }
}

object AudioTrackSelection {
    fun valid(audio: Boolean, count: Int, index: Int, supported: Boolean) = audio && index in 0 until count && supported
}
