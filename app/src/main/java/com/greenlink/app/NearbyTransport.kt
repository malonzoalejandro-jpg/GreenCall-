package com.greenlink.app

import android.content.Context
import android.media.AudioAttributes
import android.util.Base64
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
import org.json.JSONArray
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
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val outbox = appContext.getSharedPreferences("greencall_nearby_voice_outbox", Context.MODE_PRIVATE)
    private val textOutbox = appContext.getSharedPreferences("greencall_nearby_text_outbox", Context.MODE_PRIVATE)
    private val endpointNames = ConcurrentHashMap<String, String>()
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
    private val queuedVoiceBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val queuedVoiceDurations = ConcurrentHashMap<String, Int>()

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
                    synchronized(incomingAudio) { incomingAudio.write(audio) }
                    if (isMuted()) {
                        incomingMuted = true
                        stopPlayback()
                        onIncomingVoice(false)
                    } else if (!incomingMuted) {
                        player?.let { output -> runCatching { output.write(audio, 0, audio.size) } }
                    }
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
                0x04.toByte() -> runCatching {
                    val packet = JSONObject(String(bytes, 1, bytes.size - 1, StandardCharsets.UTF_8))
                    queuedVoiceBuffers[endpointId] = ByteArrayOutputStream()
                    queuedVoiceDurations[endpointId] = packet.optInt("durationMs")
                }.onFailure { onStatus("Could not start queued nearby voice transfer") }
                0x05.toByte() -> queuedVoiceBuffers[endpointId]?.let { buffer ->
                    synchronized(buffer) { buffer.write(bytes, 1, bytes.size - 1) }
                }
                0x06.toByte() -> {
                    val buffer = queuedVoiceBuffers.remove(endpointId)
                    val audio = buffer?.let { synchronized(it) { it.toByteArray() } } ?: byteArrayOf()
                    val duration = queuedVoiceDurations.remove(endpointId) ?: 0
                    if (audio.isNotEmpty()) onVoiceMail(audio, duration)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
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
                deliverPendingVoice(endpointId, endpointNames[endpointId].orEmpty())
                deliverPendingTexts(endpointId, endpointNames[endpointId].orEmpty())
                onStatus("Nearby connected · ${connected.size} device(s)")
            } else onStatus("Nearby connection failed")
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            endpointNames.remove(endpointId)
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
        val packet = JSONObject().put("type", "text").put("id", java.util.UUID.randomUUID().toString())
            .put("text", text).put("sender", displayName).put("sentAt", System.currentTimeMillis())
        val bytes = byteArrayOf(0x10) + packet.toString().toByteArray(StandardCharsets.UTF_8)
        val activeNames = connected.mapNotNull { endpointNames[it] }.toSet()
        expectedPeerNames.filter { expected -> activeNames.none { it.equals(expected, ignoreCase = true) } }
            .forEach { savePendingText(it, text) }
        connected.forEach { endpointId ->
            val peer = endpointNames[endpointId].orEmpty()
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener {
                    if (peer.isNotBlank()) savePendingText(peer, text)
                    onStatus("Nearby message saved for retry")
                }
        }
        return true
    }

    private fun savePendingText(target: String, text: String) {
        if (target.isBlank() || text.isBlank()) return
        val saved = runCatching { JSONArray(textOutbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if ((0 until saved.length()).any { saved.optJSONObject(it)?.optString("target") == target &&
                saved.optJSONObject(it)?.optString("text") == text }) return
        saved.put(JSONObject().put("id", java.util.UUID.randomUUID().toString())
            .put("target", target).put("text", text))
        textOutbox.edit().putString("pending", saved.toString()).apply()
    }

    private fun deliverPendingTexts(endpointId: String, peerName: String) {
        if (peerName.isBlank()) return
        val saved = runCatching { JSONArray(textOutbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
        for (index in 0 until saved.length()) {
            val item = saved.optJSONObject(index) ?: continue
            if (!item.optString("target").equals(peerName, ignoreCase = true)) continue
            val packet = JSONObject().put("type", "text").put("id", item.optString("id"))
                .put("text", item.optString("text")).put("sender", displayName)
                .put("sentAt", System.currentTimeMillis())
            val bytes = byteArrayOf(0x10) + packet.toString().toByteArray(StandardCharsets.UTF_8)
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnSuccessListener {
                    val current = runCatching { JSONArray(textOutbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
                    val remaining = JSONArray()
                    for (i in 0 until current.length()) {
                        val candidate = current.optJSONObject(i) ?: continue
                        if (candidate.optString("id") != item.optString("id")) remaining.put(candidate)
                    }
                    textOutbox.edit().putString("pending", remaining.toString()).apply()
                }
                .addOnFailureListener { onStatus("Queued nearby text will retry") }
        }
    }

    fun startTalking(): Boolean {
        if (!recording.compareAndSet(false, true)) return false
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
        val recorded = ByteArrayOutputStream()
        sendFrame(0x01)
        thread(name = "GreenCallNearbyMic") {
            val buffer = ByteArray(4096)
            var sentBytes = 0
            while (recording.get() && sentBytes < 800000) {
                val count = input.read(buffer, 0, minOf(buffer.size, 800000 - sentBytes))
                if (count > 0) {
                    recorded.write(buffer, 0, count)
                    sendFrame(0x02, buffer.copyOf(count))
                    sentBytes += count
                }
            }
            runCatching { input.stop() }
            input.release()
            if (recorder === input) recorder = null
            recording.set(false)
            sendFrame(0x03)
            val audio = recorded.toByteArray()
            val liveNames = connected.mapNotNull { endpointNames[it] }.toSet()
            val offlineNames = expectedPeerNames.filter { expected ->
                liveNames.none { it.equals(expected, ignoreCase = true) }
            }
            if (audio.isNotEmpty() && offlineNames.isNotEmpty()) {
                val duration = (audio.size / 32).coerceIn(1, 25000)
                offlineNames.forEach { savePendingVoice(it, audio, duration) }
                onStatus("Voice saved for ${offlineNames.size} friend(s) until nearby")
            } else if (audio.isNotEmpty()) {
                onStatus("Nearby voice sent")
            }
        }
        onStatus(if (connected.isEmpty()) "Recording offline voice message…" else "Nearby voice · talking")
        return true
    }

    fun stopTalking() {
        if (recording.getAndSet(false)) onStatus("Saving nearby voice…")
    }

    private fun savePendingVoice(target: String, audio: ByteArray, durationMs: Int) {
        val saved = runCatching { JSONArray(outbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val item = JSONObject()
            .put("id", "${System.currentTimeMillis()}-${audio.size}")
            .put("target", target)
            .put("audioBase64", Base64.encodeToString(audio, Base64.NO_WRAP))
            .put("durationMs", durationMs)
        saved.put(item)
        outbox.edit().putString("pending", saved.toString()).apply()
    }

    private fun deliverPendingVoice(endpointId: String, peerName: String) {
        if (peerName.isBlank()) return
        val saved = runCatching { JSONArray(outbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val pending = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }
            .filter { it.optString("target").equals(peerName, ignoreCase = true) }
        deliverPendingVoiceAt(endpointId, peerName, pending, 0)
    }

    private fun deliverPendingVoiceAt(
        endpointId: String,
        peerName: String,
        pending: List<JSONObject>,
        index: Int
    ) {
        if (index >= pending.size) return
        val item = pending[index]
        val audio = runCatching { Base64.decode(item.optString("audioBase64"), Base64.DEFAULT) }
            .getOrElse { onStatus("A queued voice note could not be read"); return }
        val header = JSONObject()
            .put("id", item.optString("id"))
            .put("durationMs", item.optInt("durationMs"))
        val startPacket = byteArrayOf(0x04) + header.toString().toByteArray(StandardCharsets.UTF_8)
        client.sendPayload(endpointId, Payload.fromBytes(startPacket))
            .addOnSuccessListener {
                sendQueuedVoiceChunk(endpointId, peerName, pending, index, item, audio, 0)
            }
            .addOnFailureListener { onStatus("Queued nearby voice will retry") }
    }

    private fun sendQueuedVoiceChunk(
        endpointId: String,
        peerName: String,
        pending: List<JSONObject>,
        itemIndex: Int,
        item: JSONObject,
        audio: ByteArray,
        offset: Int
    ) {
        if (offset < audio.size) {
            val end = minOf(offset + 8192, audio.size)
            val chunk = byteArrayOf(0x05) + audio.copyOfRange(offset, end)
            client.sendPayload(endpointId, Payload.fromBytes(chunk))
                .addOnSuccessListener {
                    sendQueuedVoiceChunk(endpointId, peerName, pending, itemIndex, item, audio, end)
                }
                .addOnFailureListener { onStatus("Queued nearby voice will retry") }
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(byteArrayOf(0x06)))
            .addOnSuccessListener {
                val current = runCatching { JSONArray(outbox.getString("pending", "[]") ?: "[]") }.getOrElse { JSONArray() }
                val remaining = JSONArray()
                for (i in 0 until current.length()) {
                    val candidate = current.optJSONObject(i) ?: continue
                    if (candidate.optString("id") != item.optString("id")) remaining.put(candidate)
                }
                outbox.edit().putString("pending", remaining.toString()).apply()
                onStatus("Queued nearby voice delivered")
                deliverPendingVoiceAt(endpointId, peerName, pending, itemIndex + 1)
            }
            .addOnFailureListener { onStatus("Queued nearby voice will retry") }
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
        endpointNames.clear()
        stopPlayback()
    }
}
