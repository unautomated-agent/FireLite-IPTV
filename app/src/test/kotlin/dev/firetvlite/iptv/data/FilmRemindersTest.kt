package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.film.FilmReminderCard
import dev.firetvlite.iptv.data.film.FilmReminders
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.film.ReminderShowing
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FilmRemindersTest {
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val now = time("2026-11-12T12:00:00Z")
    private fun showing(start: Long = now + 3_600_000, channel: Long = 1, title: String = "Harry Potter i Kamień Filozoficzny") =
        ReminderShowing(channel, "Polsat", start, start + 7_200_000, title, null)

    @Test fun normalizedDisplayAndOriginalTitlesMatchWithoutFilmMetadata() {
        val display = showing(title = "HARRY—POTTER: i tajemnice")
        val original = showing(channel = 2, title = "Inny tytuł").copy(originalTitle = "Harry   Potter and the Goblet of Fire")
        val unrelated = showing(channel = 3, title = "Harry i Potter")
        assertEquals(listOf(display, original), FilmReminders.matches(listOf(unrelated, original, display), now))
    }

    @Test fun dailyIncludesTodayAndAlreadyAiringButNotEndedOrTomorrow() {
        val airing = showing(now - 3_600_000)
        val today = showing()
        val ended = showing(now - 7_200_000)
        val tomorrow = showing(FilmReminders.tomorrow(now))
        val daily = FilmReminders.next(listOf(tomorrow, ended, today, airing), now, null, emptySet()) as FilmReminderCard.Daily
        assertEquals(FilmReminders.day(now), daily.date)
        assertEquals(listOf(airing, today), daily.showings)
    }

    @Test fun warsawCalendarBoundaryIsNotUtcMidnight() {
        val before = time("2026-11-12T22:59:59Z")
        val midnight = time("2026-11-12T23:00:00Z")
        val nextDay = showing(midnight + 3_600_000)
        assertTrue(FilmReminders.matches(listOf(nextDay), before).isEmpty())
        assertEquals(listOf(nextDay), FilmReminders.matches(listOf(nextDay), midnight))
        assertEquals(FilmReminders.day(before) + 1, FilmReminders.day(midnight))
        assertNotNull(FilmReminders.next(listOf(nextDay), midnight, FilmReminders.day(before), emptySet()))
        val previousDayAiring = showing(midnight - 3_600_000)
        assertEquals(listOf(previousDayAiring), FilmReminders.matches(listOf(previousDayAiring), midnight))
    }

    @Test fun queryEndUsesWarsawDstCalendarDaysRatherThanTwentyFourHours() {
        val spring = time("2026-03-28T23:00:00Z")
        val autumn = time("2026-10-24T22:00:00Z")
        assertEquals(23 * 3_600_000L, FilmReminders.tomorrow(spring) - spring)
        assertEquals(25 * 3_600_000L, FilmReminders.tomorrow(autumn) - autumn)
    }

    @Test fun tenMinuteWindowIsInclusiveAtTenMinutesAndExcludesStartedFilms() {
        val film = showing(now + FilmReminders.TEN_MINUTES)
        val ack = FilmReminders.day(now)
        assertNull(FilmReminders.next(listOf(film), now - 1, ack, emptySet()))
        assertEquals(FilmReminderCard.Soon(film), FilmReminders.next(listOf(film), now, ack, emptySet()))
        assertEquals(FilmReminderCard.Soon(film), FilmReminders.next(listOf(film), film.start - 1, ack, emptySet()))
        assertNull(FilmReminders.next(listOf(film), film.start, ack, emptySet()))
    }

    @Test fun alreadyStartedFilmsOnlyAppearInDailyCard() {
        val film = showing(now - 1)
        assertTrue(FilmReminders.next(listOf(film), now, null, emptySet()) is FilmReminderCard.Daily)
        assertNull(FilmReminders.next(listOf(film), now, FilmReminders.day(now), emptySet()))
    }

    @Test fun urgentReminderPrecedesDailyAndSurvivesDailyAcknowledgement() {
        val film = showing(now + 60_000)
        assertEquals(FilmReminderCard.Soon(film), FilmReminders.next(listOf(film), now, null, emptySet()))
        assertEquals(FilmReminderCard.Soon(film), FilmReminders.next(listOf(film), now, FilmReminders.day(now), emptySet()))
        assertTrue(FilmReminders.next(listOf(film), now, null, setOf(film.key)) is FilmReminderCard.Daily)
    }

    @Test fun shownKeysArePerChannelAndStartNotTitle() {
        val first = showing(now + 60_000)
        val second = showing(now + 120_000)
        val otherChannel = first.copy(channelId = 2)
        val all = listOf(second, first, otherChannel, first)
        assertEquals(FilmReminderCard.Soon(otherChannel),
            FilmReminders.next(all, now, FilmReminders.day(now), setOf(first.key)))
        assertEquals(FilmReminderCard.Soon(second),
            FilmReminders.next(all, now, FilmReminders.day(now), setOf(first.key, otherChannel.key)))
        assertNull(FilmReminders.next(all, now, FilmReminders.day(now), all.map { it.key }.toSet()))
    }

    @Test fun watchingLiveSkipsOnlyThatChannelsUrgentCardNotDaily() {
        val film = showing(now + 60_000)
        assertNull(FilmReminders.next(listOf(film), now, FilmReminders.day(now), emptySet(), film.channelId))
        assertEquals(FilmReminderCard.Soon(film), FilmReminders.next(listOf(film), now, FilmReminders.day(now), emptySet(), 99))
        assertTrue(FilmReminders.next(listOf(film), now, null, emptySet(), film.channelId) is FilmReminderCard.Daily)
    }

    @Test fun noMatchesOrAcknowledgedDayYieldNoDailyCard() {
        assertNull(FilmReminders.next(emptyList(), now, null, emptySet()))
        assertNull(FilmReminders.next(listOf(showing(title = "Inny film")), now, null, emptySet()))
        assertNull(FilmReminders.next(listOf(showing()), now, FilmReminders.day(now), emptySet()))
    }

    @Test fun cleanupRemovesOlderThanTwoDaysAndMalformedTimestamps() {
        val threshold = now - 2 * Films.DAY
        val keys = setOf("1:${threshold - 1}", "1:$threshold", "2:$now", "bad", "1:bad")
        assertEquals(setOf("1:$threshold", "2:$now"), FilmReminders.recentKeys(keys, now))
    }
}
