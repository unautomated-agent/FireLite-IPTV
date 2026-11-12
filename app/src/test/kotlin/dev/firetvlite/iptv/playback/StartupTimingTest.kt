package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTimingTest {
    @Test fun gateWaitConsumesTheOriginalSwitchDeadlineWithoutPreparationTime() {
        var now = 0L
        val timing = StartupTiming { now }
        assertTrue(timing.detail().contains("totalMs=0"))
        timing.enter(StartupTiming.Phase.CLOSE_GRACE)
        now = 400
        timing.enter(StartupTiming.Phase.WAITING_PREVIOUS_CONNECTION)
        now = 10_000
        assertTrue(timing.waitingForPrevious)
        assertTrue(timing.detail().contains("phase=WAITING_PREVIOUS_CONNECTION; totalMs=10000"))
        assertTrue(timing.detail().contains("CLOSE_GRACE=400ms"))
        assertTrue(timing.detail().contains("WAITING_PREVIOUS_CONNECTION=9600ms"))
        assertTrue(timing.detail().contains("PREPARING_BUFFERING=0ms"))
        timing.enter(StartupTiming.Phase.PREPARING_BUFFERING)
        now = 12_000
        assertEquals(false, timing.waitingForPrevious)
        assertTrue(timing.detail().contains("totalMs=12000"))
        assertTrue(timing.detail().contains("PREPARING_BUFFERING=2000ms"))
    }

    @Test fun recoveryTimeIsAccountedWithoutResettingTheSwitchClock() {
        var now = 0L
        val timing = StartupTiming { now }
        timing.enter(StartupTiming.Phase.PREPARING_BUFFERING)
        now = 500
        timing.enter(StartupTiming.Phase.RECOVERY_DELAY)
        now = 1000
        timing.enter(StartupTiming.Phase.WAITING_RECOVERY_CONNECTION)
        now = 2000
        timing.enter(StartupTiming.Phase.PREPARING_BUFFERING)
        now = 3000
        assertEquals(false, timing.waitingForPrevious)
        assertTrue(timing.detail().contains("totalMs=3000"))
        assertTrue(timing.detail().contains("PREPARING_BUFFERING=1500ms"))
        assertTrue(timing.detail().contains("RECOVERY_DELAY=500ms"))
        assertTrue(timing.detail().contains("WAITING_RECOVERY_CONNECTION=1000ms"))
    }
}
