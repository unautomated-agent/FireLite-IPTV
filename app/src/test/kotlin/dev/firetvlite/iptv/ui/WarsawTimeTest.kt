package dev.firetvlite.iptv.ui

import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.millisUntilNextMinute
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WarsawTimeTest {
    private fun formatted(instant: String) = clockTime(Instant.parse(instant).toEpochMilli())

    @Test fun formatsWarsawWinterAndSummerTime() {
        assertEquals("13:05", formatted("2026-01-15T12:05:00Z"))
        assertEquals("14:05", formatted("2026-07-15T12:05:00Z"))
        assertEquals("00:05", formatted("2026-01-15T23:05:00Z"))
    }

    @Test fun skipsTheMissingHourAtSpringDstTransition() {
        assertEquals("01:59", formatted("2026-03-29T00:59:59Z"))
        assertEquals("03:00", formatted("2026-03-29T01:00:00Z"))
    }

    @Test fun repeatsTheHourAtAutumnDstTransition() {
        assertEquals("02:59", formatted("2026-10-25T00:59:59Z"))
        assertEquals("02:00", formatted("2026-10-25T01:00:00Z"))
    }

    @Test fun ignoresDeviceTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("13:05", formatted("2026-01-15T12:05:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun firstFormatterInitializationInGmtStillUsesWarsaw() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
            val name = "dev.firetvlite.iptv.ui.components.WarsawTimeKt"
            val bytes = javaClass.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            val fresh = object : ClassLoader(javaClass.classLoader) {
                fun formatter() = defineClass(name, bytes, 0, bytes.size)
            }.formatter()
            val format = fresh.getMethod("clockTime", Long::class.javaPrimitiveType)
            val startup = Instant.parse("2026-11-11T21:51:00Z").toEpochMilli()
            assertEquals("22:51", format.invoke(null, startup))
            assertEquals("22:52", format.invoke(null, startup + 60_000))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun schedulesAtTheNextMinuteWithoutZeroDelay() {
        assertEquals(60_000L, millisUntilNextMinute(0))
        assertEquals(1L, millisUntilNextMinute(59_999))
        assertEquals(60_000L, millisUntilNextMinute(60_000))
        assertEquals(47_655L, millisUntilNextMinute(12_345))
        assertEquals(1L, millisUntilNextMinute(-1))
    }
}
