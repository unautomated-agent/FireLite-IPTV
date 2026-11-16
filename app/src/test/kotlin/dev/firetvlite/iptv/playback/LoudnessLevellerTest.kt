package dev.firetvlite.iptv.playback

import org.junit.Assert.*
import org.junit.Test

class LoudnessLevellerTest {
    private class Effect : LoudnessEffect {
        var enabled = 0
        var released = 0
        var failEnable = false
        var failRelease = false
        override fun enable() { enabled++; check(!failEnable) }
        override fun release() { released++; check(!failRelease) }
    }

    @Test fun waitsForARealSessionAndPcmOutputNeverAttachesToGlobalMix() {
        val sessions = mutableListOf<Int>()
        val leveller = LoudnessLeveller { sessions += it; Effect() }
        assertEquals(LoudnessStatus.Waiting, leveller.status.value)
        leveller.outputChanged(true)
        leveller.sessionChanged(-1)
        leveller.sessionChanged(0)
        assertTrue(sessions.isEmpty())
        leveller.sessionChanged(17)
        assertEquals(listOf(17), sessions)
        assertEquals(LoudnessStatus.Active, leveller.status.value)
    }

    @Test fun togglesOnlyTheEffectOnTheExistingSession() {
        val effects = mutableListOf<Effect>()
        val sessions = mutableListOf<Int>()
        val leveller = LoudnessLeveller { sessions += it; Effect().also(effects::add) }
        leveller.sessionChanged(12)
        leveller.outputChanged(true)
        leveller.sessionChanged(12)
        leveller.outputChanged(true)
        assertEquals(1, effects.size)
        leveller.setEnabled(false)
        assertEquals(1, effects[0].released)
        assertEquals(LoudnessStatus.Off, leveller.status.value)
        leveller.setEnabled(true)
        assertEquals(listOf(12, 12), sessions)
        assertEquals(1, effects[1].enabled)
        assertEquals(LoudnessStatus.Active, leveller.status.value)
    }

    @Test fun sessionReplacementAndTrackReuseReleaseOldEffects() {
        val effects = mutableListOf<Effect>()
        val leveller = LoudnessLeveller { Effect().also(effects::add) }
        leveller.sessionChanged(3)
        leveller.outputChanged(true)
        leveller.sessionChanged(4)
        assertEquals(1, effects[0].released)
        leveller.trackReleased()
        assertEquals(1, effects[1].released)
        assertEquals(LoudnessStatus.Waiting, leveller.status.value)
        leveller.outputChanged(true)
        assertEquals(3, effects.size)
        leveller.release()
        leveller.release()
        assertEquals(1, effects[2].released)
        leveller.outputChanged(true)
        assertEquals(3, effects.size)
        assertEquals(LoudnessStatus.Waiting, leveller.status.value)
    }

    @Test fun encodedAndOffloadedOutputIsNotFalselyShownAsLevelled() {
        val effects = mutableListOf<Effect>()
        val leveller = LoudnessLeveller { Effect().also(effects::add) }
        leveller.sessionChanged(1)
        leveller.outputChanged(false)
        assertTrue(effects.isEmpty())
        assertEquals(LoudnessStatus.UnsupportedOutput, leveller.status.value)
        leveller.outputChanged(true)
        leveller.outputChanged(false)
        assertEquals(1, effects.single().released)
        leveller.setEnabled(false)
        assertEquals(LoudnessStatus.Off, leveller.status.value)
    }

    @Test fun missingEffectFailsSilentlyAndCanRetryOnANewSession() {
        var available = false
        val leveller = LoudnessLeveller { check(available); Effect() }
        leveller.sessionChanged(1)
        leveller.outputChanged(true)
        assertEquals(LoudnessStatus.Unavailable, leveller.status.value)
        available = true
        leveller.sessionChanged(2)
        assertEquals(LoudnessStatus.Active, leveller.status.value)
    }

    @Test fun enableAndReleaseFailuresAreContainedWithoutLeakingTheEffect() {
        val effect = Effect().apply { failEnable = true; failRelease = true }
        val leveller = LoudnessLeveller { effect }
        leveller.sessionChanged(1)
        leveller.outputChanged(true)
        assertEquals(1, effect.released)
        assertEquals(LoudnessStatus.Unavailable, leveller.status.value)
        leveller.release()
        assertEquals(1, effect.released)
    }

    @Test fun profileOnlyAttenuatesWithNoMakeupOrInputBoost() {
        assertTrue(LoudnessProfile.INPUT_DB <= 0f)
        assertTrue(LoudnessProfile.MAKEUP_DB <= 0f)
        assertTrue(LoudnessProfile.THRESHOLD_DB < LoudnessProfile.LIMIT_DB)
        assertTrue(LoudnessProfile.LIMIT_DB < 0f)
        assertTrue(LoudnessProfile.RATIO > 1f)
    }
}
