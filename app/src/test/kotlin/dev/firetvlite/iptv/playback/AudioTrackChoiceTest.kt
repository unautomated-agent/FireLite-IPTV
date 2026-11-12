package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class AudioTrackChoiceTest {
    private fun track(language: String? = null, label: String? = null, role: Int = 0) =
        AudioTrackChoice(0, 2, language, label, null, false, roleFlags = role)

    @Test fun languageAliasesHaveCompactChipsAndReadableNames() {
        for ((aliases, expected) in listOf(
            listOf("pol", "pl", "PL-pl") to ("PL" to "Polski"),
            listOf("eng", "en", "en-US") to ("ENG" to "Angielski"),
            listOf("ger", "deu", "de") to ("DE" to "Niemiecki"),
            listOf("fre", "fra", "fr") to ("FR" to "Francuski"),
            listOf("rus", "ru") to ("RU" to "Rosyjski"),
            listOf("ukr", "uk") to ("UA" to "Ukraiński"),
            listOf("spa", "es") to ("ES" to "Hiszpański"),
            listOf("ita", "it") to ("IT" to "Włoski"),
        )) for (alias in aliases) {
            assertEquals(expected.first, track(alias).languageChip)
            assertEquals(expected.second, track(alias).readableName)
        }
    }

    @Test fun missingLanguageUsesLabelOrTrackNumber() {
        for (language in listOf(null, "", "und", "UND")) {
            assertEquals("Komentarz", track(language, "Komentarz").languageChip)
            assertEquals("Ścieżka 3", track(language, " ").languageChip)
            assertEquals("Ścieżka 3", track(language).readableName)
        }
    }

    @Test fun accessibilityUsesFlagsAndWholeWordHintsWithoutFalseAdMatches() {
        assertTrue(track(role = C.ROLE_FLAG_DESCRIBES_VIDEO).audioDescription)
        for (label in listOf("AD", "Polski AD", "Audiodeskrypcja", "Audio description")) {
            assertTrue(label, track("pl", label).audioDescription)
        }
        assertTrue(track("qad").audioDescription)
        assertFalse(track("pl", "Radio dodatkowe").audioDescription)
        assertTrue(track(role = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND).hearingImpaired)
        for (label in listOf("Dla niesłyszących", "SDH", "Hard of hearing")) {
            assertTrue(label, track(label = label).hearingImpaired)
        }
        assertFalse(track().audioDescription)
        assertFalse(track().hearingImpaired)
    }

    @Test fun metadataRetainsSelectionFlagsAndOptionalChannelDetails() {
        val choice = track("pl").copy(selectionFlags = C.SELECTION_FLAG_DEFAULT, channelCount = 6, codec = "ac-3")
        assertEquals(C.SELECTION_FLAG_DEFAULT, choice.selectionFlags)
        assertEquals("5.1 · ac-3", choice.details)
        assertEquals("Stereo", track().copy(channelCount = 2).details)
        assertEquals("", track().details)
    }
}
