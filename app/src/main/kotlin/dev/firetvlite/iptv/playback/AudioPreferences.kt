package dev.firetvlite.iptv.playback

import java.util.Locale

internal object AudioPreferences {
    fun languages(locales: List<Locale>): List<String> =
        (listOf("pl", "pol") + locales.map { it.toLanguageTag() })
            .filter { it.isNotBlank() && it != "und" }.distinct()
}
