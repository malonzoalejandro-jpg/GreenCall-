package com.greenlink.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.Context
import android.util.Base64
import kotlin.concurrent.thread

object LocalVoicePlayer {
    @Volatile private var active: AudioTrack? = null

    fun play(encoded: String, context: Context) {
        stop()
        thread(name = "GreenCallLocalVoicePlayback") {
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isEmpty()) return@runCatching
                val minimum = AudioTrack.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minimum <= 0) return@runCatching
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(16000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minimum, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                active = track
                track.play()
                var offset = 0
                while (offset < bytes.size && active === track) {
                    val count = minOf(8192, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + count)
                    val boosted = AudioAmplifier.apply(context, chunk)
                    val written = track.write(boosted, 0, boosted.size)
                    if (written <= 0) break
                    offset += written
                }
                runCatching { track.stop() }
                track.release()
                if (active === track) active = null
            }
        }
    }

    fun stop() {
        active?.let { track ->
            active = null
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}
