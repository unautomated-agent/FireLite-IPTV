package dev.firetvlite.iptv.data

import org.junit.Assert.*
import org.junit.Test

class ChannelUsageTest {
    @Test fun lifetimeCountersAreRetainedWhileRankingScoreDecaysWithoutRevivingOldHabits() {
        val first = ChannelUsage(1, "channel").add(10, 300_000, 1000)
        assertEquals(10L, first.selections)
        assertEquals(300_000L, first.watchMs)
        assertEquals(11.0, first.scoreAt(1000), 0.00001)
        val later = 1000 + ChannelUsage.HALF_LIFE_MS.toLong()
        assertEquals(5.5, first.scoreAt(later), 0.00001)
        val next = first.add(1, 300_000, later)
        assertEquals(11L, next.selections)
        assertEquals(600_000L, next.watchMs)
        assertEquals(7.5, next.scoreAt(later), 0.00001)
    }

    @Test fun clockRollbackDoesNotAmplifyUsageAndEmptyUsageHasNoWeight() {
        val empty = ChannelUsage(1, "channel")
        assertEquals(0.0, empty.scoreAt(Long.MAX_VALUE), 0.0)
        val recorded = empty.add(1, 0, 1000)
        assertEquals(1.0, recorded.scoreAt(0), 0.0)
        assertEquals(1000L, recorded.add(1, 0, 0).updatedAt)
    }
}
