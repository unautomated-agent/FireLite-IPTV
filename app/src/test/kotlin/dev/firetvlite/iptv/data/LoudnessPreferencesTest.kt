package dev.firetvlite.iptv.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class LoudnessPreferencesTest {
    @Test fun defaultsOnAndPersistsLiveTogglesAcrossRecreation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val backend = Backend()
        try {
            fun open() = LoudnessPreferences(scope, { backend.preferences }, Dispatchers.Unconfined, Dispatchers.Unconfined)
            val first = open()
            assertTrue(first.enabled.value)
            first.setEnabled(false)
            assertFalse(first.enabled.value)
            assertEquals(false, backend.saved)
            val reopened = open()
            assertFalse(reopened.enabled.value)
            reopened.setEnabled(true)
            assertTrue(open().enabled.value)
        } finally { scope.cancel() }
    }

    @Test fun unavailablePreferencesFailSilentlyAndStillAllowLiveToggling() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val preferences = LoudnessPreferences(scope, { error("unavailable") }, Dispatchers.Unconfined, Dispatchers.Unconfined)
            assertTrue(preferences.enabled.value)
            preferences.setEnabled(false)
            assertFalse(preferences.enabled.value)
            preferences.setEnabled(true)
            assertTrue(preferences.enabled.value)
        } finally { scope.cancel() }
    }

    private class Backend {
        var saved: Boolean? = null
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { assertEquals("loudness_enabled", args!![0]); saved = args[1] as Boolean; proxy }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> saved ?: args!![1]
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
