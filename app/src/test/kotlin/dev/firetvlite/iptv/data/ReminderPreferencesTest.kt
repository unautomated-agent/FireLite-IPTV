package dev.firetvlite.iptv.data

import android.content.SharedPreferences
import dev.firetvlite.iptv.data.film.FilmReminders
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.film.ReminderPreferences
import dev.firetvlite.iptv.data.film.ReminderShowing
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class ReminderPreferencesTest {
    @Test fun acknowledgementsAndShownKeysSurviveRecreationAndPruneOldEntries() {
        val values = mutableMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "putLong", "putStringSet" -> { values[a[0] as String] = a[1]!!; editor }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val a = args.orEmpty()
            when (method.name) {
                "getLong", "getStringSet" -> values[a[0] as String] ?: a[1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
        val first = ReminderPreferences(preferences)
        assertNull(first.state.value.date)
        assertTrue(first.state.value.shown.isEmpty())
        val now = 10 * Films.DAY
        val showing = ReminderShowing(1, "Polsat", now + 60_000, now + 7_200_000, "Harry Potter", null)
        first.acknowledge(FilmReminders.day(now))
        first.markShown(showing, now)
        val reopened = ReminderPreferences(preferences)
        assertEquals(first.state.value, reopened.state.value)
        assertEquals(setOf(showing.key), reopened.state.value.shown)
        reopened.prune(now + 3 * Films.DAY)
        assertTrue(ReminderPreferences(preferences).state.value.shown.isEmpty())
        assertEquals(FilmReminders.day(now), reopened.state.value.date)
    }
}
