package dev.firetvlite.iptv.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LoudnessStatus { Off, Waiting, Active, Unavailable, UnsupportedOutput }

internal interface LoudnessEffect {
    fun enable()
    fun release()
}

internal object LoudnessProfile {
    const val INPUT_DB = 0f
    const val THRESHOLD_DB = -18f
    const val RATIO = 3f
    const val MAKEUP_DB = 0f
    const val LIMIT_DB = -3f
}

/** Main-thread owner of an effect on this player's session, never the global output mix. */
internal class LoudnessLeveller(
    private val create: (Int) -> LoudnessEffect = ::SessionLoudnessEffect,
) {
    private var enabled = true
    private var session = 0
    private var supported: Boolean? = null
    private var effect: LoudnessEffect? = null
    private val mutableStatus = MutableStateFlow(LoudnessStatus.Waiting)
    val status = mutableStatus.asStateFlow()

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        update()
    }

    fun sessionChanged(id: Int) {
        if (session == id) return
        detach()
        session = id
        update()
    }

    fun outputChanged(isPcm: Boolean) {
        if (supported == isPcm) return
        supported = isPcm
        update()
    }

    fun release() {
        detach()
        session = 0
        supported = null
        update()
    }

    fun trackReleased() {
        detach()
        supported = null
        update()
    }

    private fun detach() {
        val old = effect
        effect = null
        runCatching { old?.release() }
    }

    private fun update() {
        when {
            !enabled -> { detach(); mutableStatus.value = LoudnessStatus.Off }
            supported == false -> { detach(); mutableStatus.value = LoudnessStatus.UnsupportedOutput }
            session <= 0 || supported == null -> { detach(); mutableStatus.value = LoudnessStatus.Waiting }
            effect == null -> {
                runCatching {
                    create(session).also { effect = it }.enable()
                }.onSuccess {
                    mutableStatus.value = LoudnessStatus.Active
                }.onFailure {
                    detach()
                    mutableStatus.value = LoudnessStatus.Unavailable
                }
            }
        }
    }
}

private class SessionLoudnessEffect(session: Int) : LoudnessEffect {
    private val effect: DynamicsProcessing

    init {
        require(session > 0)
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
            false, 0, true, 1, false, 0, true,
        ).setInputGainAllChannelsTo(LoudnessProfile.INPUT_DB).build()
        val instance = DynamicsProcessing(0, session, config)
        try {
            instance.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(
                true, 20_000f, 5f, 300f, LoudnessProfile.RATIO, LoudnessProfile.THRESHOLD_DB,
                6f, -90f, 1f, 0f, LoudnessProfile.MAKEUP_DB,
            ))
            instance.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(
                true, true, 0, 1f, 80f, 20f, LoudnessProfile.LIMIT_DB, 0f,
            ))
            effect = instance
        } catch (error: Exception) {
            runCatching { instance.release() }
            throw error
        }
    }

    override fun enable() {
        check(effect.hasControl())
        check(effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled)
    }

    override fun release() = effect.release()
}
