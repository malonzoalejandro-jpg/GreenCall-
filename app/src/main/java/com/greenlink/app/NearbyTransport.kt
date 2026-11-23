package com.greenlink.app

import android.content.Context
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct device-to-device transport. It does not require the GreenCall server or internet,
 * but both users must have GreenCall open, grant Nearby permissions, and be within radio range.
 */
class NearbyTransport(
    context: Context,
    private val displayName: String,
    private val expectedPeerName: String,
    private val onStatus: (String) -> Unit,
    private val onText: (JSONObject) -> Unit
) {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val serviceId = "com.greencall.app.nearby"
    private val strategy = Strategy.P2P_CLUSTER
    private val connected = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var started = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            runCatching {
                val packet = JSONObject(String(bytes, StandardCharsets.UTF_8))
                if (packet.optString("type") == "text" && packet.optString("text").isNotBlank()) {
                    onText(packet)
                }
            }.onFailure { onStatus("Received an unreadable nearby packet") }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!info.endpointName.equals(expectedPeerName, ignoreCase = true)) {
                client.rejectConnection(endpointId)
                return
            }
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { onStatus("Nearby connection was rejected") }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                0 -> {
                    connected.add(endpointId)
                    onStatus("Nearby connected · ${connected.size} device(s)")
                }
                else -> onStatus("Nearby connection failed")
            }
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            onStatus(if (connected.isEmpty()) "Searching nearby devices…" else "Nearby connected · ${connected.size} device(s)")
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName.equals(expectedPeerName, ignoreCase = true) && endpointId !in connected) {
                client.requestConnection(displayName.take(32), endpointId, lifecycle)
                    .addOnFailureListener { onStatus("Could not connect to nearby device") }
            }
        }
        override fun onEndpointLost(endpointId: String) {
            connected.remove(endpointId)
        }
    }

    fun start() {
        if (expectedPeerName.isBlank()) {
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
        val payload = Payload.fromBytes(packet.toString().toByteArray(StandardCharsets.UTF_8))
        client.sendPayload(connected.toList(), payload)
            .addOnFailureListener { onStatus("Nearby message could not be delivered") }
        return true
    }

    fun hasPeers(): Boolean = connected.isNotEmpty()

    fun stop() {
        started = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        connected.clear()
    }
}
