package com.greenlink.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Base64
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.client.Ack
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class WalkieTalkieController(
    private val context: Context,
    private val token: String,
    private val conversationId: String,
    private var muted: Boolean,
    private val onStatus: (String) -> Unit,
    private val onIncoming: (Boolean) -> Unit,
    private val onVoicemail: () -> Unit,
    private val onMessage: (JSONObject) -> Unit
) {
    private val sampleRate = 16000
    private val maxVoiceBytes = 800000
    private val recording = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val audioBytes = ByteArrayOutputStream()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var liveStream = false
    @Volatile private var startedAt = 0L

    init {
        connect()
    }

    private fun connect() {
        try {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build()
            val client = IO.socket(GreenCallApi.BASE_URL, options)
            socket = client
            client.on(Socket.EVENT_CONNECT) {
                client.emit("conversation:join", JSONObject().put("conversationId", conversationId))
                client.emit("voice:mute", JSONObject().put("muted", muted))
                onStatus("Voice connected")
                flushPendingVoicemails()
            }
            client.on(Socket.EVENT_DISCONNECT) {
                liveStream = false
                onStatus("Reconnecting voice…")
            }
            client.on(Socket.EVENT_CONNECT_ERROR) { onStatus("Voice connection unavailable") }
            client.on("voice:start") { args ->
                if (!muted) {
                    onIncoming(true)
                    startPlayback()
                }
            }
            client.on("voice:chunk") { args ->
                if (!muted && args.isNotEmpty()) {
                    val packet = args[0] as? JSONObject ?: return@on
                    val encoded = packet.optString("data")
                    if (encoded.isNotBlank()) {
                        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
                        if (bytes != null) { val boosted = AudioAmplifier.apply(context, bytes); player?.write(boosted, 0, boosted.size) }
                    }
                }
            }
            client.on("voice:end") {
                stopPlayback()
                onIncoming(false)
            }
            client.on("voicemail:new") { onVoicemail() }
            client.on("message:new") { args ->
                val item = args.firstOrNull() as? JSONObject
                if (item != null) onMessage(item)
            }
            client.connect()
        } catch (error: Exception) {
            onStatus(error.message ?: "Voice connection failed")
        }
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun setMuted(value: Boolean) {
        muted = value
        socket?.emit("voice:mute", JSONObject().put("muted", value))
        if (value) {
            stopPlayback()
            onIncoming(false)
        }
    }

    fun startTalking(): Boolean {
        if (muted || !recording.compareAndSet(false, true)) return false
        val minSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minSize <= 0) {
            recording.set(false)
            onStatus("Microphone is not available")
            return false
        }
        val input = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minSize, 4096)
        )
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            recording.set(false)
            onStatus("Could not open the microphone")
            return false
        }
        synchronized(audioBytes) { audioBytes.reset() }
        recorder = input
        startedAt = System.currentTimeMillis()
        liveStream = socket?.connected() == true
        if (liveStream) socket?.emit("voice:start", JSONObject().put("conversationId", conversationId))
        else onStatus("Recording offline voice note…")
        try {
            input.startRecording()
        } catch (error: Exception) {
            input.release()
            recorder = null
            recording.set(false)
            onStatus("Microphone could not start")
            return false
        }
        thread(name = "GreenCallMic") {
            val buffer = ByteArray(4096)
            while (recording.get()) {
                val count = input.read(buffer, 0, buffer.size)
                if (count > 0) {
                    val copy = buffer.copyOf(count)
                    var shouldSend = false
                    synchronized(audioBytes) {
                        if (audioBytes.size() + count <= maxVoiceBytes) audioBytes.write(copy)
                        shouldSend = true
                    }
                    if (shouldSend && liveStream) {
                        socket?.emit("voice:chunk", JSONObject()
                            .put("conversationId", conversationId)
                            .put("data", Base64.encodeToString(copy, Base64.NO_WRAP)))
                    }
                }
            }
            runCatching { input.stop() }
            input.release()
            if (recorder === input) recorder = null
        }
        onStatus("You're talking…")
        return true
    }

    fun stopTalking() {
        if (!recording.getAndSet(false)) return
        val bytes = synchronized(audioBytes) { audioBytes.toByteArray() }
        val duration = (System.currentTimeMillis() - startedAt).coerceIn(0, 25000).toInt()
        if (bytes.isNotEmpty()) {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val packet = JSONObject()
                .put("conversationId", conversationId)
                .put("durationMs", duration)
                .put("audioBase64", encoded)
            val client = socket
            if (client?.connected() == true) {
                client.emit("voice:finish", packet, Ack { args ->
                    val result = args.firstOrNull() as? JSONObject
                    if (result?.optBoolean("ok") == true) onStatus("Voice sent or saved as voicemail")
                    else sendRestFallback(encoded, duration)
                })
                onStatus("Sending voice…")
            } else {
                sendRestFallback(encoded, duration)
            }
        } else {
            onStatus("No voice was recorded")
        }
        liveStream = false
    }

    private val queueKey get() = "pending_voice_$conversationId"

    private fun savePendingVoicemail(encoded: String, durationMs: Int) {
        synchronized(context) {
            val prefs = context.getSharedPreferences("greencall", Context.MODE_PRIVATE)
            val queue = runCatching { JSONArray(prefs.getString(queueKey, "[]") ?: "[]") }.getOrElse { JSONArray() }
            queue.put(JSONObject()
                .put("audioBase64", encoded)
                .put("durationMs", durationMs)
                .put("createdAt", System.currentTimeMillis()))
            prefs.edit().putString(queueKey, queue.toString()).apply()
        }
        onStatus("No internet — voice saved on this device and queued for delivery")
    }

    private fun flushPendingVoicemails() {
        if (token.isBlank() || conversationId.startsWith("nearby-")) return
        thread(name = "GreenCallVoiceOutbox") {
            val prefs = context.getSharedPreferences("greencall", Context.MODE_PRIVATE)
            val queue = synchronized(context) {
                runCatching { JSONArray(prefs.getString(queueKey, "[]") ?: "[]") }.getOrElse { JSONArray() }
            }
            if (queue.length() == 0) return@thread
            val remaining = JSONArray()
            for (index in 0 until queue.length()) {
                val item = queue.optJSONObject(index) ?: continue
                try {
                    GreenCallApi.sendVoicemail(token, conversationId, item.optString("audioBase64"), item.optInt("durationMs"))
                } catch (_: Exception) {
                    remaining.put(item)
                }
            }
            synchronized(context) {
                prefs.edit().putString(queueKey, remaining.toString()).apply()
            }
            if (remaining.length() == 0) onStatus("Queued voice messages delivered")
            else onStatus("${remaining.length()} voice message(s) still waiting for internet")
        }
    }

    private fun sendRestFallback(encoded: String, durationMs: Int) {
        thread(name = "GreenCallVoiceUpload") {
            try {
                GreenCallApi.sendVoicemail(token, conversationId, encoded, durationMs)
                onStatus("Voice message saved for delivery")
            } catch (_: Exception) {
                savePendingVoicemail(encoded, durationMs)
            }
        }
    }

    private fun startPlayback() {
        if (!playing.compareAndSet(false, true)) return
        val minSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minSize <= 0) {
            playing.set(false)
            return
        }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minSize, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        player = output
        output.play()
    }

    private fun stopPlayback() {
        playing.set(false)
        player?.let { output ->
            runCatching { output.stop() }
            output.release()
        }
        player = null
    }

    fun playVoicemail(encoded: String) {
        thread(name = "GreenCallVoicemail") {
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val minSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minSize, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                output.play()
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(8192, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + count)
                    val boosted = AudioAmplifier.apply(context, chunk)
                    output.write(boosted, 0, boosted.size)
                    offset += count
                }
                output.stop()
                output.release()
            }
        }
    }

    fun close() {
        stopTalking()
        stopPlayback()
        socket?.disconnect()
        socket?.off()
        socket?.close()
        socket = null
    }
}
