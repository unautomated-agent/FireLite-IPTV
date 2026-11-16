package dev.firetvlite.iptv.ui.screens

import androidx.compose.ui.focus.FocusRequester
import dev.firetvlite.iptv.CrashReportStore
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsNavigationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun conciseErrorSkipsRealCrashMetadataAndPreservesPlaybackErrorPriority() {
        val store = CrashReportStore(File(folder.root, "crash.txt"))
        store.handler("test-version", null).uncaughtException(Thread.currentThread(), IllegalStateException("fixture failure"))
        val crash = "Ostatni raport błędu:\n${store.read()}"
        assertEquals("Uncaught crash: java.lang.IllegalStateException: fixture failure",
            settingsLastError("Ostatni błąd odtwarzania:\nBrak\n$crash"))
        assertEquals("HTTP 404", settingsLastError("Ostatni błąd odtwarzania:\nHTTP 404\n$crash"))
    }

    @Test fun sectionStateDefaultsSafelyAndRestoresEverySection() {
        assertEquals(SettingsSection.Sources, SettingsSection.restored(""))
        assertEquals(SettingsSection.Sources, SettingsSection.restored("obsolete"))
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.restored(it.name)) }
        assertEquals(6, SettingsSection.entries.size)
    }

    @Test fun paneFocusRestoresRegisteredLastControlOrUsesDefaultTraversal() {
        var last: String? = null
        val pane = SettingsPaneFocus({ last }, { last = it })
        assertNull(pane.target())
        val first = FocusRequester()
        val second = FocusRequester()
        pane.controls["first"] = first
        pane.controls["second"] = second
        assertNull(pane.target())
        pane.changed("first")
        assertSame(first, pane.target())
        pane.changed("second")
        assertSame(second, pane.target())
        pane.controls.remove("second")
        assertNull(pane.target())
        pane.controls.clear()
        assertNull(pane.target())
    }

    @Test fun reportScrollingIsBoundedAndDownAtBottomTransfersToClose() {
        assertEquals(80, reportScrollTarget(0, 250, true, 80))
        assertEquals(160, reportScrollTarget(0, 500, true, 160))
        assertEquals(250, reportScrollTarget(240, 250, true, 80))
        assertNull(reportScrollTarget(250, 250, true, 80))
        assertNull(reportScrollTarget(0, 0, true, 80))
        assertEquals(0, reportScrollTarget(50, 250, false, 80))
        assertEquals(0, reportScrollTarget(0, 250, false, 80))
    }

    @Test fun conciseErrorHandlesMissingReportsAndRetainsOnlyTheFirstErrorLine() {
        assertNull(settingsLastError(""))
        assertNull(settingsLastError("Ostatni błąd odtwarzania:\nBrak\nOstatni raport błędu:\nBrak"))
        assertEquals("HTTP 404", settingsLastError("Ostatni błąd odtwarzania:\nHTTP 404\nDetails\nOstatni raport błędu:\nCrash"))
        assertEquals("Uncaught crash: failure", settingsLastError("Ostatni błąd odtwarzania:\nBrak\nOstatni raport błędu:\nUncaught crash: failure\nStack"))
        assertEquals("Main-thread stall: 3500ms", settingsLastError(
            "Ostatni raport błędu:\nMain-thread stall: 3500ms\nStack\n---\nFireLite old\nUncaught crash: older failure"))
    }
}
