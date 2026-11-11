package dev.firetvlite.iptv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelFocusTargetTest {
    private val target = ChannelFocusTarget("channel-url", 12)

    @Test fun resolvesIdentityBeforeOldPositionAfterPagingUpdates() {
        assertEquals(15, target.index(15, 40))
        assertEquals(12, target.index(null, 40))
    }

    @Test fun removedRowsClampToASurvivingNeighbourAndEmptyListsWait() {
        assertEquals(4, target.index(null, 5))
        assertEquals(0, target.index(null, 1))
        assertEquals(null, target.index(null, 0))
    }
}
