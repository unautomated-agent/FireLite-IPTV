package dev.firetvlite.iptv.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveStartPreferencesTest {
    private val now = 1_800_000_000_000L

    @Test fun programmeIsRequestedHalfAMinuteAfterTheGuideTimeByDefault() {
        assertEquals(now - 3_600_000 + 30_000, ArchiveStartPreferences(prefs()).time(now - 3_600_000, now))
    }

    @Test fun programmeThatHasJustStartedIsRequestedAtTheGuideTime() {
        assertEquals(now - 35_000, ArchiveStartPreferences(prefs()).time(now - 35_000, now))
    }

    @Test fun choiceCyclesThroughTheOptionsAndSurvivesARestart() {
        val values = mutableMapOf<String, Int>()
        val preferences = ArchiveStartPreferences(prefs(values))
        val seen = List(ArchiveStartPreferences.OPTIONS.size) { preferences.next(); preferences.delay.value }
        assertEquals(listOf(45, 60, 90, 0, 15, 30), seen)
        preferences.next()
        assertEquals(45, ArchiveStartPreferences(prefs(values)).delay.value)
        values["archive_start_delay"] = 7
        assertEquals(30, ArchiveStartPreferences(prefs(values)).delay.value)
    }

    private fun prefs(values: MutableMap<String, Int> = mutableMapOf()): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putInt" -> { values[args!![0] as String] = args[1] as Int; editor }
                "apply" -> null
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }
}
