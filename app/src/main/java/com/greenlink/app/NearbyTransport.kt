package com.greenlink.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct nearby text and push-to-talk transport. Both users must keep the chat open
 * and remain within Bluetooth/Wi-Fi radio range. No internet or server is required.
 */
class NearbyTransport(
    context: Context,
    private val displayName: String,
    private val expectedPeerNames: Set<String>,
    private val isMuted: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onText: (JSONObject) -> Unit,
    private val onIncomingVoice: (Boolean) -> Unit,
    private val onVoiceMail: (ByteArray, Int) -> Unit
) {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val serviceId = "com.greencall.app.nearby"
    private val strategy = Strategy.P2P_CLUSTER
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val recording = AtomicBoolean(false)
    private val sampleRate = 16000
    @Volatile private var started = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var incomingMuted = false
    @Volatile private var incomingStartedAt = 0L
    private val incomingAudio = ByteArrayOutputStream()

    private fun sendFrame(type: Byte, data: ByteArray = byteArrayOf()) {
        if (connected.isEmpty()) return
        val packet = ByteArray(data.size + 1)
        packet[0] = type
        if (data.isNotEmpty()) System.arraycopy(data, 0, packet, 1, data.size)
        client.sendPayload(connected.toList(), Payload.fromBytes(packet))
            .addOnFailureListener { onStatus("Nearby transfer failed") }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            if (bytes.isEmpty()) return
            when (bytes[0]) {
                0x10.toByte() -> runCatching {
                    val packet = JSONObject(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
                    if (packet.optString("type") == "text" && packet.optString("text").isNotBlank()) onText(packet)
                }.onFailure { onStatus("Received an unreadable nearby message") }
                0x01.toByte() -> {
                    incomingStartedAt = System.currentTimeMillis()
                    incomingMuted = isMuted()
                    synchronized(incomingAudio) { incomingAudio.reset() }
                    if (!incomingMuted) {
                        startPlayback()
                        onIncomingVoice(true)
                    }
                }
                0x02.toByte() -> {
                    val audio = bytes.copyOfRange(1, bytes.size)
                    if (incomingMuted) synchronized(incomingAudio) { incomingAudio.write(audio) }
                    else player?.let { output -> runCatching { output.write(audio, 0, audio.size) } }
                }
                0x03.toByte() -> {
                    val duration = (System.currentTimeMillis() - incomingStartedAt).coerceIn(0, 25000).toInt()
                    if (incomingMuted) {
                        val audio = synchronized(incomingAudio) { incomingAudio.toByteArray() }
                        if (audio.isNotEmpty()) onVoiceMail(audio, duration)
                    } else {
                        stopPlayback()
                        onIncomingVoice(false)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (expectedPeerNames.none { it.equals(info.endpointName, ignoreCase = true) }) {
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { onStatus("Nearby connection was rejected") }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected.add(endpointId)
                onStatus("Nearby connected · ${connected.size} device(s)")
            } else onStatus("Nearby connection failed")
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            onStatus(if (connected.isEmpty()) "Searching nearby devices…" else "Nearby connected · ${connected.size} device(s)")
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val isInitiator = displayName.compareTo(info.endpointName, ignoreCase = true) < 0
            if (isInitiator && expectedPeerNames.any { it.equals(info.endpointName, ignoreCase = true) } && endpointId !in connected) {
                client.requestConnection(displayName.take(32), endpointId, lifecycle)
                    .addOnFailureListener { onStatus("Could not connect to nearby device") }
            }
        }
        override fun onEndpointLost(endpointId: String) { connected.remove(endpointId) }
    }

    fun start() {
        if (expectedPeerNames.isEmpty()) {
            onStatus("Nearby direct mode needs a selected friend")
            return
        }
        if (started) return
        started = true
        onStatus("Searching nearby devices…")
        client.startAdvertising(displayName.take(32), serviceId, lifecycle,
            com.google.android.gms.nearby.connection.AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { onStatus("Nearby advertising unavailable: ${it.localizedMessage ?: "permission or radio issue"}") }
        client.startDiscovery(serviceId, discovery,
            com.google.android.gms.nearby.connection.DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { onStatus("Nearby discovery unavailable: ${it.localizedMessage ?: "permission or radio issue"}") }
    }

    fun sendText(text: String): Boolean {
        if (connected.isEmpty() || text.isBlank()) return false
        val packet = JSONObject().put("type", "text").put("text", text)
            .put("sender", displayName).put("sentAt", System.currentTimeMillis())
        sendFrame(0x10, packet.toString().toByteArray(StandardCharsets.UTF_8))
        return true
    }

    fun startTalking(): Boolean {
        if (connected.isEmpty() || !recording.compareAndSet(false, true)) return false
        val minSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) { recording.set(false); onStatus("Microphone unavailable"); return false }
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, 8192))
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            recording.set(false)
            onStatus("Could not open the microphone")
            return false
        }
        try { input.startRecording() } catch (_: Exception) {
            input.release(); recording.set(false); onStatus("Microphone could not start"); return false
        }
        recorder = input
        sendFrame(0x01)
        thread(name = "GreenCallNearbyMic") {
            val buffer = ByteArray(4096)
            var sentBytes = 0
            while (recording.get() && sentBytes < 800000) {
                val count = input.read(buffer, 0, minOf(buffer.size, 800000 - sentBytes))
                if (count > 0) {
                    sendFrame(0x02, buffer.copyOf(count))
                    sentBytes += count
                }
            }
            runCatching { input.stop() }
            input.release()
            if (recorder === input) recorder = null
            if (recording.getAndSet(false)) sendFrame(0x03)
        }
        onStatus("Nearby voice · talking")
        return true
    }

    fun stopTalking() {
        if (recording.getAndSet(false)) {
            sendFrame(0x03)
            onStatus("Nearby voice sent")
        }
    }

    private fun startPlayback() {
        if (player != null) return
        val minSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) return
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minSize, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        player = output
        output.play()
    }

    private fun stopPlayback() {
        player?.let { output -> runCatching { output.stop() }; output.release() }
        player = null
    }

    fun hasPeers(): Boolean = connected.isNotEmpty()

    fun stop() {
        stopTalking()
        started = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        connected.clear()
        stopPlayback()
    }
}
