package dev.firetvlite.iptv.playback

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekGestureTest {
    @Test fun stepsAccelerateAtOneTwoAndFourSecondsAndReleaseResetsTheHold() {
        val gesture = SeekGesture()
        gesture.press(TvKey.Right, 100, false)
        gesture.press(TvKey.Right, 1_100, true)
        gesture.press(TvKey.Right, 2_100, true)
        gesture.press(TvKey.Right, 4_100, true)
        assertEquals(150_000L, gesture.release(TvKey.Right))
        assertNull(gesture.release(TvKey.Right))
        gesture.press(TvKey.Right, 8_100, false)
        assertEquals(10_000L, gesture.release(TvKey.Right))
    }

    @Test fun repeatCadenceIsBoundedAndDirectionChangesResetAcceleration() {
        val gesture = SeekGesture()
        gesture.press(TvKey.Left, 0, false)
        gesture.press(TvKey.Left, 50, true)
        gesture.press(TvKey.Left, 199, true)
        gesture.press(TvKey.Left, 200, true)
        gesture.press(TvKey.Left, 4_000, true)
        gesture.press(TvKey.Right, 4_100, false)
        assertNull(gesture.release(TvKey.Left))
        assertEquals(-90_000L, gesture.release(TvKey.Right))
    }

    @Test fun transportKeysUseTheirLargerStepAndCancellationDoesNotCommit() {
        for ((key, sign) in listOf(TvKey.Rewind to -1, TvKey.FastForward to 1)) {
            val gesture = SeekGesture()
            gesture.press(key, 0, false)
            gesture.press(key, 2_000, true)
            assertEquals(sign * 300_000L, gesture.release(key))
            gesture.press(key, 4_000, false)
            gesture.cancel()
            assertNull(gesture.release(key))
        }
        val empty = SeekGesture()
        empty.press(TvKey.Up, 0, false)
        assertNull(empty.release(TvKey.Up))
    }

    @Test fun holdingPastTheDebounceDeadlineNeverSeeksAndReleaseCommitsOnlyOnce() = runBlocking {
        val seeks = mutableListOf<Long>()
        val debouncer = SeekDebouncer(this, seeks::add, delayMs = 40)
        val gesture = SeekGesture()
        debouncer.hold()
        gesture.press(TvKey.Right, 0, false)
        gesture.press(TvKey.Right, 4_000, true)
        delay(80)
        assertEquals(emptyList<Long>(), seeks)
        debouncer.release(gesture.release(TvKey.Right)!!)
        assertEquals(emptyList<Long>(), seeks)
        delay(80)
        assertEquals(listOf(90_000L), seeks)
        delay(80)
        assertEquals(1, seeks.size)
    }

    @Test fun rapidPressesMergeAndASecondHoldSuspendsTheEarlierCommit() = runBlocking {
        val seeks = mutableListOf<Long>()
        val debouncer = SeekDebouncer(this, seeks::add, delayMs = 60)
        debouncer.release(10_000)
        delay(20)
        debouncer.hold()
        delay(100)
        assertEquals(emptyList<Long>(), seeks)
        debouncer.release(40_000)
        debouncer.release(-10_000)
        delay(100)
        assertEquals(listOf(40_000L), seeks)
    }

    @Test fun cancellationAndNetZeroMovementDoNotSeek() = runBlocking {
        val seeks = mutableListOf<Long>()
        val debouncer = SeekDebouncer(this, seeks::add, delayMs = 40)
        debouncer.release(60_000)
        debouncer.cancel()
        delay(80)
        debouncer.release(10_000)
        debouncer.release(-10_000)
        delay(80)
        assertEquals(emptyList<Long>(), seeks)
    }

    @Test fun remoteSeekKeysOnlySubmitOnReleaseAndNavigationCancelsQueuedSeeks() {
        val screen = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/PlayerScreen.kt").readText()
            .substringAfter("private fun PlayerContent(")
        assertTrue(screen.contains("seekGesture.release(mapped)?.let { delta ->"))
        assertTrue(screen.contains("viewModel.finishSeek(delta)"))
        assertTrue(screen.contains("if (!repeat) viewModel.beginSeek()"))
        assertTrue(screen.contains("key(mapped, repeat)"))
        assertFalse(screen.contains("viewModel.seek("))
        assertFalse(screen.contains("viewModel.jumpToProgrammeStart()"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        assertTrue(model.contains("SeekDebouncer(viewModelScope, ::seek)"))
        for (method in listOf("select", "settlePendingZap", "zap", "close")) {
            val body = model.substringAfter("fun $method(").substringBefore("\n    }")
            assertTrue(method, body.contains("seekDebouncer.cancel()"))
        }
    }

    @Test fun previewTracksOffsetAndMultiplierWithoutReleasingTheGesture() {
        val gesture = SeekGesture()
        assertNull(gesture.preview)
        gesture.press(TvKey.Left, 0, false)
        assertEquals(SeekPreview(-10_000, 1), gesture.preview)
        gesture.press(TvKey.Left, 1_000, true)
        assertEquals(SeekPreview(-30_000, 2), gesture.preview)
        gesture.press(TvKey.Left, 2_000, true)
        assertEquals(SeekPreview(-70_000, 4), gesture.preview)
        gesture.press(TvKey.Left, 4_000, true)
        assertEquals(SeekPreview(-150_000, 8), gesture.preview)
        assertNull(gesture.release(TvKey.Right))
        assertEquals(SeekPreview(-150_000, 8), gesture.preview)
        assertEquals(-150_000L, gesture.release(TvKey.Left))
        assertNull(gesture.preview)
        gesture.press(TvKey.Right, 5_000, false)
        assertEquals(SeekPreview(10_000, 1), gesture.preview)
        gesture.cancel()
        assertNull(gesture.preview)
    }

    @Test fun feedbackIncludesEarlierDebouncedPressesAndResetsAfterCommitOrCancel() {
        var feedback = SeekFeedback()
        assertNull(feedback.preview)
        feedback = feedback.hold(SeekPreview(10_000, 1)).release(10_000)
        assertNull(feedback.held)
        assertEquals(SeekPreview(10_000, 1), feedback.preview)
        feedback = feedback.hold(SeekPreview(70_000, 4))
        assertEquals(SeekPreview(80_000, 4), feedback.preview)
        feedback = feedback.release(70_000)
        assertEquals(SeekPreview(80_000, 4), feedback.preview)
        assertNull(SeekFeedback().preview)
        feedback = feedback.hold(SeekPreview(-80_000, 2)).release(-80_000)
        assertNull(feedback.preview)
    }
}
