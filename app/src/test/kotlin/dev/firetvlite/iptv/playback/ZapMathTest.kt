package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ZapMathTest {

    @Test
    fun movesWithinTheList() {
        assertEquals(3, ZapMath.wrap(2, 1, 10))
        assertEquals(1, ZapMath.wrap(2, -1, 10))
    }

    @Test
    fun wrapsAroundAtBothEnds() {
        assertEquals(0, ZapMath.wrap(9, 1, 10))
        assertEquals(9, ZapMath.wrap(0, -1, 10))
    }

    @Test
    fun aSingleItemStaysPut() {
        assertEquals(0, ZapMath.wrap(0, 1, 1))
        assertEquals(0, ZapMath.wrap(0, -1, 1))
    }
}
