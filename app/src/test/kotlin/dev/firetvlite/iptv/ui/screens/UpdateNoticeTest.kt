package dev.firetvlite.iptv.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UpdateNoticeTest {
    @Test fun fullscreenHintHasNoInteractiveControlsAndExpiresWithoutDismissingPanelCard() {
        val shell = File("src/main/kotlin/dev/firetvlite/iptv/FireLiteApp.kt").readText()
        val hint = shell.substringAfter("if (mode.fullFrame) {").substringBefore("} else {")
        assertTrue(hint.contains("if (hintVisible) FLText"))
        assertTrue(hint.contains("R.string.update_hint"))
        assertFalse(hint.contains("Popup"))
        assertFalse(hint.contains("Button"))
        assertFalse(hint.contains("focus"))
        val expiration = shell.substringAfter("LaunchedEffect(release.tag) {").substringBefore("}")
        assertTrue(expiration.contains("delay(8_000)"))
        assertTrue(expiration.contains("hintVisible = false"))
        assertFalse(expiration.contains("dismissedUpdate"))
        assertTrue(shell.contains("PopupProperties(focusable = true, dismissOnBackPress = true)"))
        assertTrue(shell.contains("onDismissRequest = { dismissedUpdate = release.tag }"))
        assertTrue(shell.contains("focus.requestFocusWhenReady()"))
        assertTrue(shell.contains("Modifier.focusRequester(focus)"))
    }
}
