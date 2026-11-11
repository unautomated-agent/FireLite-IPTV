package dev.firetvlite.iptv.playback

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPreferencesTest {
    @Test fun polishPrecedesDeviceLanguagesAndDuplicatesAreRemoved() {
        assertEquals(listOf("pl", "pol", "de-DE", "en-US"), AudioPreferences.languages(
            listOf(Locale.forLanguageTag("pl"), Locale.GERMANY, Locale.US, Locale.GERMANY)))
    }

    @Test fun absentDeviceLocaleStillPrefersPolish() {
        assertEquals(listOf("pl", "pol"), AudioPreferences.languages(emptyList()))
        assertEquals(listOf("pl", "pol"), AudioPreferences.languages(listOf(Locale.ROOT)))
    }
}
