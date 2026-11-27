package com.greenlink.app

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * Hardware DSP for push-to-talk capture. Echo cancellation prevents speaker
 * output from being re-captured by the microphone and sent back to the peer.
 */
internal class VoiceInputEffects(audioSessionId: Int) : AutoCloseable {
    private val echoCanceler = runCatching {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        } else null
    }.getOrNull()

    private val noiseSuppressor = runCatching {
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        } else null
    }.getOrNull()

    private val gainControl = runCatching {
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        } else null
    }.getOrNull()

    override fun close() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
    }
}
