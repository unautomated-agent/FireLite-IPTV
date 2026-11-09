package dev.firetvlite.iptv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceDensityTest {

    @Test
    fun fullHdScreenGetsDensityTwo() {
        assertEquals(2f, referenceDensity(1920), 0.0001f)
    }

    @Test
    fun ultraHdScreenGetsDensityFour() {
        assertEquals(4f, referenceDensity(3840), 0.0001f)
    }

    @Test
    fun hdScreenKeepsTheSameDpWidth() {
        val widthDp = 1280 / referenceDensity(1280)
        assertEquals(REFERENCE_WIDTH_DP, widthDp, 0.0001f)
    }
}
