package dev.firetvlite.iptv.playback

import org.junit.Assert.*
import org.junit.Test

class TvPlayerControlsTest {
    @Test fun bannerExpandsAndScrollsAndDownOnlyHidesCompact() {
        val compact = TvPlayerControls().key(TvKey.Up, 100).first
        assertEquals(BannerState.Compact, compact.banner)
        assertEquals(8_100, compact.deadline)
        assertEquals(BannerState.Hidden, compact.key(TvKey.Down, 200).first.banner)
        val expanded = compact.key(TvKey.Up, 200).first
        assertEquals(BannerState.Expanded, expanded.banner)
        assertEquals(TvAction.ScrollUp, expanded.key(TvKey.Up, 300).second)
        assertEquals(TvAction.ScrollDown, expanded.key(TvKey.Down, 400).second)
        assertEquals(BannerState.Expanded, expanded.key(TvKey.Down, 400).first.banner)
    }

    @Test fun timerHidesExactlyEightSecondsAfterLastKeyInBothBannerStates() {
        for (state in listOf(BannerState.Compact, BannerState.Expanded)) {
            val initial = TvPlayerControls(state).touch(100)
            assertEquals(state, initial.expire(8_099).banner)
            assertEquals(BannerState.Hidden, initial.expire(8_100).banner)
            val reset = initial.key(TvKey.Right, 8_000).first
            assertEquals(state, reset.expire(15_999).banner)
            assertEquals(BannerState.Hidden, reset.expire(16_000).banner)
        }
        assertEquals(TvPlayerControls(), TvPlayerControls().touch(20_000).expire(90_000))
    }

    @Test fun downTogglesOnlyWithNoBannerAndBackNeverLeavesPlayback() {
        assertEquals(TvAction.PreviousChannel, TvPlayerControls().key(TvKey.Down, 0).second)
        for (state in BannerState.entries) {
            val result = TvPlayerControls(state).key(TvKey.Back, 100)
            assertEquals(TvAction.None, result.second)
            assertEquals(BannerState.Hidden, result.first.banner)
        }
    }

    @Test fun seekAndPlaybackKeysUseTheSpecifiedActions() {
        val mappings = mapOf(TvKey.Left to TvAction.Back10, TvKey.Right to TvAction.Forward10,
            TvKey.Rewind to TvAction.Back60, TvKey.FastForward to TvAction.Forward60,
            TvKey.PlayPause to TvAction.TogglePause)
        mappings.forEach { (key, action) -> assertEquals(action, TvPlayerControls().key(key, 0).second) }
        assertEquals(-10_000L, TvAction.Back10.seekMs)
        assertEquals(10_000L, TvAction.Forward10.seekMs)
        assertEquals(-60_000L, TvAction.Back60.seekMs)
        assertEquals(60_000L, TvAction.Forward60.seekMs)
    }

    @Test fun shortAndLongOkAndMenuHaveDistinctDestinationsAndHideBanner() {
        mapOf(TvKey.Ok to TvAction.Channels, TvKey.LongOk to TvAction.Guide,
            TvKey.Menu to TvAction.History, TvKey.LongMenu to TvAction.QuickMenu).forEach { (key, action) ->
            val result = TvPlayerControls(BannerState.Expanded).key(key, 100)
            assertEquals(action, result.second)
            assertEquals(BannerState.Hidden, result.first.banner)
            val press = RemotePress()
            press.begin()
            assertTrue(press.longPress())
            assertFalse(press.longPress())
            assertFalse(press.release())
            assertFalse(press.release())
            press.begin()
            assertTrue(press.release())
            assertFalse(press.release())
        }
    }

    @Test fun previousChannelAlternatesLastTwoAndIgnoresRepeatedCurrent() {
        val history = PreviousChannel<String> { it }
        assertNull(history.previous)
        history.watched("a")
        assertNull(history.previous)
        history.watched("b")
        history.watched("b")
        assertEquals("a", history.previous)
        history.watched(history.previous!!)
        assertEquals("b", history.previous)
        history.watched(history.previous!!)
        assertEquals("a", history.previous)
        history.clear()
        assertNull(history.previous)
    }

    @Test fun startupOnlyResumesAnActivePlaylistRecentChannelBeforeUserInteraction() {
        assertTrue(StartupResume.allowed(true, true, PlayerMode.Closed, true))
        assertFalse(StartupResume.allowed(false, true, PlayerMode.Closed, true))
        assertFalse(StartupResume.allowed(true, false, PlayerMode.Closed, true))
        assertFalse(StartupResume.allowed(true, true, PlayerMode.Closed, false))
        PlayerMode.entries.filter { it != PlayerMode.Closed }.forEach {
            assertFalse(StartupResume.allowed(true, true, it, true))
        }
    }

    @Test fun previousChannelIsSeededFromOnlyTheTwoMostRecentChannelsAtStartup() {
        val history = PreviousChannel<String> { it }
        history.seedRecent(listOf("current", "previous", "older"))
        assertEquals("previous", history.previous)
        history.watched(history.previous!!)
        assertEquals("current", history.previous)
        history.seedRecent(listOf("only"))
        assertNull(history.previous)
        history.seedRecent(emptyList())
        assertNull(history.previous)
    }

    @Test fun categorySwitchWrapsAndHandlesEmptyOrMissingCurrent() {
        assertNull(CategorySwitch.next(emptyList<String>(), "a", 1))
        assertEquals("a", CategorySwitch.next(listOf("a"), "a", -1))
        assertEquals("b", CategorySwitch.next(listOf("a", "b"), "a", -1))
        assertEquals("a", CategorySwitch.next(listOf("a", "b"), "b", 1))
        assertEquals("b", CategorySwitch.next(listOf("a", "b"), "missing", 1))
    }

    @Test fun audioSelectionValidatesTypeBoundsAndSupportAndProvidesReadableLabels() {
        assertTrue(AudioTrackSelection.valid(true, 2, 1, true))
        assertFalse(AudioTrackSelection.valid(false, 2, 1, true))
        assertFalse(AudioTrackSelection.valid(true, 0, 0, true))
        assertFalse(AudioTrackSelection.valid(true, 2, -1, true))
        assertFalse(AudioTrackSelection.valid(true, 2, 2, true))
        assertFalse(AudioTrackSelection.valid(true, 2, 1, false))
        assertEquals("pl · Polski · ac-3", AudioTrackChoice(0, 1, "pl", "Polski", "ac-3", true).title)
        assertEquals("Ścieżka 1", AudioTrackChoice(0, 0, null, "", null, false).title)
    }
}
