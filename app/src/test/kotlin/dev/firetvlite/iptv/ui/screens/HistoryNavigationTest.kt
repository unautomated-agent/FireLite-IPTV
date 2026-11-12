package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.TimelineItem
import dev.firetvlite.iptv.playback.RemotePress
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryNavigationTest {
    @Test fun initialPositionPlacesLiveAtThreeQuartersOfTheViewport() {
        val position = HistoryNavigation.initialPosition(30, 1000, 50, 0)
        assertEquals(HistoryNavigation.InitialPosition(15, 0), position)
        val fractional = HistoryNavigation.initialPosition(30, 1000, 40, 4)
        assertEquals(HistoryNavigation.InitialPosition(12, 42), fractional)
        assertEquals(750, (30 - fractional.index) * 44 - fractional.offset)
    }

    @Test fun initialPositionClampsWhenThereAreFewPastRowsAndHandlesEmptyOrUnmeasuredLists() {
        assertEquals(HistoryNavigation.InitialPosition(0, 0), HistoryNavigation.initialPosition(2, 1000, 40, 4))
        assertEquals(HistoryNavigation.InitialPosition(0, 0), HistoryNavigation.initialPosition(-1, 1000, 40, 4))
        assertEquals(HistoryNavigation.InitialPosition(0, 0), HistoryNavigation.initialPosition(0, 1000, 40, 4))
        assertEquals(HistoryNavigation.InitialPosition(30, 0), HistoryNavigation.initialPosition(30, 0, 40, 4))
        assertEquals(HistoryNavigation.InitialPosition(30, 0), HistoryNavigation.initialPosition(30, 1000, 0, 4))
    }

    @Test fun initialPlacementScalesWithResolutionAndKeepsLiveFocus() {
        val high = HistoryNavigation.initialPosition(30, 840, 52, 8)
        val low = HistoryNavigation.initialPosition(30, 560, 34, 6)
        assertEquals(630, (30 - high.index) * 60 - high.offset)
        assertEquals(420, (30 - low.index) * 40 - low.offset)
        assertEquals(items.lastIndex, HistoryFocus(now, live = true).index(items))
    }

    @Test fun positioningWaitsForRoomAndRunsOnlyOncePerOpenedTarget() {
        val screen = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/ChannelHistoryList.kt").readText()
        assertTrue(screen.contains("LaunchedEffect(target, items, programmes != null)"))
        assertTrue(screen.contains("if (initiallyPositioned || programmes == null) return@LaunchedEffect"))
        assertTrue(screen.contains("listState.scrollToItem(position.index, position.offset)"))
        assertTrue(screen.contains("initiallyPositioned = true"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
            .substringAfter("fun playTimeline(item: TimelineItem)").substringBefore("TimelineAction.Past ->")
        assertTrue(model.contains("item.programme)"))
        assertTrue(!model.contains("if (item.live) null"))
    }

    @Test fun openingMenuReleaseCannotDismissHistoryWithoutANewPress() {
        val menu = RemotePress()
        assertTrue(!menu.release())
        menu.begin()
        assertTrue(menu.release())
        assertTrue(!menu.release())
    }

    @Test fun dismissRestoresOverlayTargetWithoutDispatchingPlayback() {
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val dismiss = model.substringAfter("fun dismissTimeline()").substringBefore("fun openLiveProgramme(")
        assertTrue(dismiss.contains("if (target.fromOverlay)"))
        assertTrue(dismiss.contains("BrowserTarget(target.group, target.channel, ++targetToken)"))
        assertTrue(dismiss.contains("setMode(PlayerMode.Overlay)"))
        assertTrue(!dismiss.contains("playback."))
        assertTrue(!dismiss.contains("open("))
        val open = model.substringAfter("fun openTimeline(").substringBefore("fun closeTimeline()")
        assertTrue(open.contains("fromOverlay = mutableMode.value == PlayerMode.Overlay"))
        assertTrue(!open.contains("playback."))
        val screen = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerScreen.kt").readText()
        assertTrue(screen.contains("if (timeline != null) viewModel.dismissTimeline()"))
        val history = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/ChannelHistoryList.kt").readText()
        assertTrue(history.contains("menuPress.release()) player.dismissTimeline()"))
        val browser = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/LiveTvScreen.kt").readText()
        val overlayRow = browser.substringAfter(") else ChannelRowItem(").substringBefore("Spacer(Modifier.fillMaxWidth()")
        assertTrue(overlayRow.contains("onMenu = { openChannelHistory() }"))
        assertTrue(!overlayRow.contains("toggleFavorite"))
    }

    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val now = time("2026-11-11T20:15:00Z")
    private val items = ChannelTimeline.history(ChannelTimeline.items(emptyList(), now, now - 2 * ChannelTimeline.DAY))

    @Test fun opensAtLiveAndKeepsItAfterEpgReload() {
        val focus = HistoryFocus(now, live = true)
        assertEquals(items.lastIndex, focus.index(items))
        val reloaded = listOf(TimelineItem(items.first().start - 1_000, items.first().start)) + items
        assertEquals(reloaded.lastIndex, focus.index(reloaded))
    }

    @Test fun keepsSelectedTimeRatherThanItsOldIndexWhenRowsChange() {
        val focus = HistoryFocus(items[8].start)
        assertEquals(8, focus.index(items))
        assertEquals(9, focus.index(listOf(TimelineItem(items.first().start - 1_000, items.first().start)) + items))
        assertEquals(7, focus.index(items.filterIndexed { index, _ -> index != 8 }))
        assertEquals(0, focus.index(emptyList()))
    }

    @Test fun dayKeysMoveInTheExpectedDirectionAndClamp() {
        val live = HistoryFocus(now, live = true)
        val yesterday = HistoryNavigation.jumpDay(items, live, -1, now)
        assertEquals(time("2026-11-10T20:00:00Z"), yesterday.time)
        val oldest = HistoryNavigation.jumpDay(items, yesterday, -1, now)
        assertEquals(items.first().start, oldest.time)
        assertEquals(oldest, HistoryNavigation.jumpDay(items, oldest, -1, now))
        assertEquals(live, HistoryNavigation.jumpDay(items, live, 1, now))
        assertEquals(time("2026-11-11T20:00:00Z"), HistoryNavigation.jumpDay(items, yesterday, 1, now).time)
        assertEquals(live, HistoryNavigation.jumpDay(emptyList(), live, -1, now))
        assertEquals(live, HistoryNavigation.jumpDay(items, live, 0, now))
    }

    @Test fun dayJumpUsesWarsawCalendarAcrossDstNotFixed24Hours() {
        val end = time("2026-03-29T12:15:00Z")
        val history = ChannelTimeline.history(ChannelTimeline.items(emptyList(), end, end - 2 * ChannelTimeline.DAY))
        val previous = HistoryNavigation.jumpDay(history, HistoryFocus(end, live = true), -1, end)
        assertEquals(time("2026-03-28T13:00:00Z"), previous.time)
    }

    @Test fun headersUseWarsawDatesAndPolishNames() {
        assertEquals("Dziś", HistoryNavigation.dayLabel(now, now, "Dziś", "Wczoraj", "Jutro"))
        assertEquals("Wczoraj", HistoryNavigation.dayLabel(now - ChannelTimeline.DAY, now, "Dziś", "Wczoraj", "Jutro"))
        assertEquals("Jutro", HistoryNavigation.dayLabel(now + ChannelTimeline.DAY, now, "Dziś", "Wczoraj", "Jutro"))
        assertEquals("Pon 9.11", HistoryNavigation.dayLabel(now - 2 * ChannelTimeline.DAY, now, "Dziś", "Wczoraj", "Jutro"))
        assertEquals("Dziś", HistoryNavigation.dayLabel(time("2026-11-10T23:30:00Z"), time("2026-11-11T00:30:00Z"), "Dziś", "Wczoraj", "Jutro"))
    }

    @Test fun relativeHeadersUseResourceSuppliedLabels() {
        assertEquals("today resource", HistoryNavigation.dayLabel(now, now, "today resource", "yesterday resource", "tomorrow resource"))
        assertEquals("yesterday resource", HistoryNavigation.dayLabel(now - ChannelTimeline.DAY, now, "today resource", "yesterday resource", "tomorrow resource"))
        assertEquals("tomorrow resource", HistoryNavigation.dayLabel(now + ChannelTimeline.DAY, now, "today resource", "yesterday resource", "tomorrow resource"))
    }

    @Test fun futureDayJumpsReachTheEpgEndSkipEmptyDaysAndClamp() {
        val future = listOf("2026-11-12T20:00:00Z", "2026-11-13T20:00:00Z", "2026-11-15T20:00:00Z").map {
            TimelineItem(time(it), time(it) + ChannelTimeline.HOUR, future = true)
        }
        val history = items + future
        val tomorrow = HistoryNavigation.jumpDay(history, HistoryFocus(now, live = true), 1, now)
        assertEquals(future[0].start, tomorrow.time)
        val friday = HistoryNavigation.jumpDay(history, tomorrow, 1, now)
        assertEquals(future[1].start, friday.time)
        val end = HistoryNavigation.jumpDay(history, friday, 1, now)
        assertEquals(future[2].start, end.time)
        assertEquals(end, HistoryNavigation.jumpDay(history, end, 1, now))
        assertEquals(friday, HistoryNavigation.jumpDay(history, end, -1, now))
        assertEquals(tomorrow, HistoryNavigation.jumpDay(history, friday, -1, now))
    }

    @Test fun forwardDayJumpsPreserveWarsawTimeAcrossBothDstTransitions() {
        for ((today, tomorrow) in listOf(
            "2026-03-28T12:15:00Z" to "2026-03-29T11:15:00Z",
            "2026-10-24T11:15:00Z" to "2026-10-25T12:15:00Z",
        )) {
            val start = time(today)
            val future = time(tomorrow)
            val history = listOf(TimelineItem(start, Long.MAX_VALUE, live = true),
                TimelineItem(future, future + ChannelTimeline.HOUR, future = true))
            assertEquals(future, HistoryNavigation.jumpDay(history, HistoryFocus(start, live = true), 1, start).time)
        }
    }

    @Test fun liveRemainsInitiallySelectedWhenUpcomingRowsArrive() {
        val focus = HistoryFocus(now, live = true)
        val upcoming = TimelineItem(now + ChannelTimeline.HOUR, now + 2 * ChannelTimeline.HOUR, future = true)
        val reloaded = items + upcoming
        assertEquals(items.lastIndex, focus.index(reloaded))
        assertTrue(reloaded[focus.index(reloaded)].live)
    }

    @Test fun upcomingFocusUsesItsExactStartEvenWithOverlapsAndReloads() {
        val first = TimelineItem(now + ChannelTimeline.HOUR, now + 4 * ChannelTimeline.HOUR, future = true)
        val second = TimelineItem(now + 2 * ChannelTimeline.HOUR, now + 3 * ChannelTimeline.HOUR, future = true)
        val history = items + first + second
        val focus = HistoryFocus(second.start)
        assertEquals(history.lastIndex, focus.index(history))
        val reloaded = listOf(TimelineItem(items.first().start - 1_000, items.first().start)) + history
        assertEquals(reloaded.lastIndex, focus.index(reloaded))
    }

    @Test fun futureSelectionOnlyShowsTheStartTimeAndNeverDispatchesPlayback() {
        val future = TimelineItem(now + ChannelTimeline.HOUR, now + 2 * ChannelTimeline.HOUR, future = true)
        val hints = mutableListOf<Long>()
        val played = mutableListOf<TimelineItem>()
        HistoryNavigation.select(future, { hints.add(it) }, { played.add(it) })
        assertEquals(listOf(future.start), hints)
        assertTrue(played.isEmpty())
        val past = items.first()
        val live = items.last()
        HistoryNavigation.select(past, { hints.add(it) }, { played.add(it) })
        HistoryNavigation.select(live, { hints.add(it) }, { played.add(it) })
        assertEquals(listOf(past, live), played)
        assertEquals(listOf(future.start), hints)
    }
}
