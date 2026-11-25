package com.greenlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import kotlin.concurrent.thread

class VoiceSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var activeToken = ""
    @Volatile private var currentUserId = ""
    private val conversationTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GreenCall:VoiceSession").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                SERVICE_CHANNEL, "GreenCall background connection", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps GreenCall connected while you use other apps"; setShowBadge(false) })
            manager.createNotificationChannel(NotificationChannel(
                MESSAGE_CHANNEL, "GreenCall messages", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Message previews and friend activity"; setShowBadge(true) })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeToken = intent?.getStringExtra("token").orEmpty().ifBlank {
            getSharedPreferences("greencall", MODE_PRIVATE).getString("token", "").orEmpty()
        }
        currentUserId = intent?.getStringExtra("user_id").orEmpty().ifBlank {
            getSharedPreferences("greencall", MODE_PRIVATE).getString("user_id", "").orEmpty()
        }
        val notification: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_greencall)
            .setContentTitle("GreenCall is connected")
            .setContentText("Background message and voice connection is active.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(SERVICE_NOTIFICATION_ID, notification)
        if (activeToken.isNotBlank()) connectMessages(activeToken)
        return START_STICKY
    }

    private fun connectMessages(token: String) {
        if (socket?.connected() == true) return
        runCatching { socket?.disconnect(); socket?.close() }
        try {
            val client = IO.socket(GreenCallApi.BASE_URL, IO.Options.builder()
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build())
            socket = client
            client.on(Socket.EVENT_CONNECT) {
                thread(name = "GreenCallBackgroundRooms") {
                    runCatching {
                        val conversations = GreenCallApi.getConversations(token)
                        for (index in 0 until conversations.length()) {
                            val item = conversations.optJSONObject(index) ?: continue
                            val id = item.optString("id")
                            if (id.isBlank()) continue
                            val members = item.optJSONArray("members")
                            var title = item.optString("title", "GreenCall message")
                            if (item.optString("kind") == "direct" && members != null) {
                                for (memberIndex in 0 until members.length()) {
                                    val member = members.optJSONObject(memberIndex) ?: continue
                                    if (member.optString("id") != currentUserId) {
                                        title = member.optString("displayName", member.optString("username", title))
                                        break
                                    }
                                }
                            }
                            conversationTitles[id] = title
                            client.emit("conversation:join", JSONObject().put("conversationId", id))
                        }
                    }
                }
            }
            client.on("call:incoming") { args ->
                val call = args.firstOrNull() as? JSONObject ?: return@on
                showIncomingCallNotification(
                    call.optString("callId"),
                    call.optString("conversationId"),
                    call.optString("callerId"),
                    call.optString("callerName", "GreenCall friend"),
                    call.optBoolean("video")
                )
            }
            client.on("message:new") { args ->
                val message = args.firstOrNull() as? JSONObject ?: return@on
                if (message.optString("sender_id") == currentUserId) return@on
                val conversationId = message.optString("conversation_id")
                val title = conversationTitles[conversationId] ?: "New GreenCall message"
                showMessageNotification(title, message.optString("body"), conversationId)
            }
            client.connect()
        } catch (_: Exception) { }
    }

    private fun showIncomingCallNotification(callId: String, conversationId: String, callerId: String, callerName: String, video: Boolean) {
        val open = Intent(this, CallActivity::class.java)
            .putExtra("token", activeToken)
            .putExtra("conversation_id", conversationId)
            .putExtra("peer_id", callerId)
            .putExtra("peer_name", callerName)
            .putExtra("call_id", callId)
            .putExtra("video", video)
            .putExtra("incoming", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, callId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val kind = if (video) "video" else "voice"
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_greencall)
            .setContentTitle("$kind call from $callerName")
            .setContentText("Tap to answer in GreenCall")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java).notify(callId.hashCode(), notification)
    }

    private fun showMessageNotification(title: String, body: String, conversationId: String) {
        val open = Intent(this, MainActivity::class.java)
            .putExtra("conversation_id", conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, conversationId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_greencall)
            .setContentTitle(title)
            .setContentText(body.ifBlank { "Sent you a message" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Sent you a message" }))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(conversationId.hashCode(), notification)
    }

    override fun onDestroy() {
        runCatching { socket?.disconnect(); socket?.off(); socket?.close() }
        socket = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SERVICE_CHANNEL = "greencall_background"
        private const val MESSAGE_CHANNEL = "greencall_messages"
        private const val SERVICE_NOTIFICATION_ID = 7301
    }
}
