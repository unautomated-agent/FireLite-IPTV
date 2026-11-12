package dev.firetvlite.iptv.data

import android.content.SharedPreferences
import dev.firetvlite.iptv.ui.components.clockTime
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ClockPreferencesTest {
    @Test fun clockDefaultsToVisibleForAnUnconfiguredInstall() {
        val backend = Backend()
        assertTrue(ClockPreferences(backend.preferences).visible.value)
        assertTrue(backend.values.isEmpty())
    }

    @Test fun togglesUpdateImmediatelyAndPersistAcrossPreferenceRecreation() {
        val backend = Backend()
        val first = ClockPreferences(backend.preferences)
        first.setVisible(false)
        assertFalse(first.visible.value)
        val reopened = ClockPreferences(backend.preferences)
        assertFalse(reopened.visible.value)
        reopened.setVisible(true)
        assertTrue(reopened.visible.value)
        assertTrue(ClockPreferences(backend.preferences).visible.value)
    }

    @Test fun disablingTheBoxDoesNotChangeWarsawBannerTimeFormatting() {
        val preferences = ClockPreferences(Backend().preferences)
        val winter = Instant.parse("2026-11-11T21:00:00Z").toEpochMilli()
        val summer = Instant.parse("2026-07-11T21:00:00Z").toEpochMilli()
        for (visible in listOf(true, false, true)) {
            preferences.setVisible(visible)
            assertEquals("22:00", clockTime(winter))
            assertEquals("23:00", clockTime(summer))
        }
    }

    private class Backend {
        val values = mutableMapOf<String, Boolean>()
        val preferences: SharedPreferences

        init {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                val arguments = args.orEmpty()
                when (method.name) {
                    "putBoolean" -> { values[arguments[0] as String] = arguments[1] as Boolean; editor }
                    "apply" -> null
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                val arguments = args.orEmpty()
                when (method.name) {
                    "getBoolean" -> values[arguments[0] as String] ?: arguments[1]
                    "edit" -> editor
                    else -> error("Unexpected preference method: ${method.name}")
                }
            } as SharedPreferences
        }
    }
}
