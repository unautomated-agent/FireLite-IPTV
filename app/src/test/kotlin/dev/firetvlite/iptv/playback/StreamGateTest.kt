package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamGateTest {

    @Test
    fun theFirstRequestSwitches() {
        val gate = StreamGate()

        assertEquals(StreamGate.Action.Switch, gate.onRequest("http://h/1"))
        assertEquals("http://h/1", gate.activeUrl)
    }

    @Test
    fun theSameStreamIsNeverOpenedTwice() {
        val gate = StreamGate()
        gate.onRequest("http://h/1")

        assertEquals(StreamGate.Action.Ignore, gate.onRequest("http://h/1"))
        assertEquals(StreamGate.Action.Ignore, gate.onRequest("http://h/1"))
    }

    @Test
    fun anotherStreamReplacesTheActiveOne() {
        val gate = StreamGate()
        gate.onRequest("http://h/1")

        assertEquals(StreamGate.Action.Switch, gate.onRequest("http://h/2"))
        assertEquals("http://h/2", gate.activeUrl)
    }

    @Test
    fun afterAStopTheSameStreamMayStartAgain() {
        val gate = StreamGate()
        gate.onRequest("http://h/1")
        gate.onStopped()

        assertNull(gate.activeUrl)
        assertEquals(StreamGate.Action.Switch, gate.onRequest("http://h/1"))
    }
}
