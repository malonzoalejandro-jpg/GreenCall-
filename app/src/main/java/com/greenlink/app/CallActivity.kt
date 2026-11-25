package com.greenlink.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import io.socket.client.IO
import io.socket.client.Socket
import java.util.Collections

class CallActivity : ComponentActivity() {
    private lateinit var token: String
    private lateinit var conversationId: String
    private lateinit var peerId: String
    private lateinit var peerName: String
    private lateinit var callId: String
    private var videoCall = false
    private var incoming = false
    private var accepted = false
    private var connected = false
    private var muted = false
    private var cameraEnabled = true
    private var socket: Socket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var egl: EglBase? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var remoteVideo: VideoTrack? = null
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private lateinit var acceptButton: Button
    private lateinit var endButton: Button
    private lateinit var muteButton: Button
    private lateinit var cameraButton: Button
    private lateinit var speakerButton: Button
    private var speakerOn = true
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("token").orEmpty()
        conversationId = intent.getStringExtra("conversation_id").orEmpty()
        peerId = intent.getStringExtra("peer_id").orEmpty()
        peerName = intent.getStringExtra("peer_name").orEmpty().ifBlank { "GreenCall friend" }
        callId = intent.getStringExtra("call_id").orEmpty().ifBlank { java.util.UUID.randomUUID().toString() }
        videoCall = intent.getBooleanExtra("video", false)
        incoming = intent.getBooleanExtra("incoming", false)
        if (token.isBlank() || conversationId.isBlank() || peerId.isBlank()) {
            finish()
            return
        }
        buildUi()
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7201)
        else connectSignaling()
    }

    private fun buildUi() {
        window.statusBarColor = android.graphics.Color.rgb(13, 43, 30)
        window.navigationBarColor = android.graphics.Color.rgb(13, 43, 30)
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.rgb(13, 43, 30)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 36, 24, 28)
        }
        status = TextView(this).apply {
            text = if (incoming) "Incoming ${if (videoCall) "video" else "voice"} call" else "Calling $peerName…"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        column.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.VERTICAL }
        acceptButton = Button(this).apply {
            text = "Accept call"
            visibility = if (incoming) View.VISIBLE else View.GONE
            setOnClickListener { acceptCall() }
        }
        endButton = Button(this).apply {
            text = if (incoming) "Decline" else "End call"
            setOnClickListener { endCall(true) }
        }
        muteButton = Button(this).apply {
            text = "Mute microphone"
            visibility = if (incoming) View.GONE else View.VISIBLE
            setOnClickListener {
                muted = !muted
                localAudio?.setEnabled(!muted)
                text = if (muted) "Unmute microphone" else "Mute microphone"
            }
        }
        cameraButton = Button(this).apply {
            text = "Turn camera off"
            visibility = if (videoCall && !incoming) View.VISIBLE else View.GONE
            setOnClickListener {
                cameraEnabled = !cameraEnabled
                localVideo?.setEnabled(cameraEnabled)
                text = if (cameraEnabled) "Turn camera off" else "Turn camera on"
            }
        }
        speakerButton = Button(this).apply {
            text = "Speaker on"
            visibility = if (incoming) View.GONE else View.VISIBLE
            setOnClickListener {
                speakerOn = !speakerOn
                audioManager.isSpeakerphoneOn = speakerOn
                text = if (speakerOn) "Speaker on" else "Speaker off"
            }
        }
        actions.addView(acceptButton)
        actions.addView(muteButton)
        actions.addView(cameraButton)
        actions.addView(speakerButton)
        actions.addView(endButton)
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))
        root.addView(column)
        setContentView(root)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private val audioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7201) {
            if (this.permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) connectSignaling()
            else {
                status.text = "Microphone and camera permissions are required for calls."
                acceptButton.visibility = View.GONE
            }
        }
    }

    private fun connectSignaling() {
        if (socket != null) return
        status.text = if (incoming) "Incoming call from $peerName" else "Connecting securely…"
        try {
            val client = IO.socket(GreenCallApi.BASE_URL, IO.Options.builder()
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .build())
            socket = client
            client.on(Socket.EVENT_CONNECT) {
                runOnUiThread {
                    if (!incoming) {
                        client.emit("call:invite", JSONObject()
                            .put("callId", callId)
                            .put("conversationId", conversationId)
                            .put("recipientId", peerId)
                            .put("video", videoCall))
                        status.text = "Calling $peerName…"
                    } else status.text = "$peerName is calling"
                }
            }
            client.on("call:accepted") {
                runOnUiThread {
                    accepted = true
                    acceptButton.visibility = View.GONE
                    status.text = "Connecting call…"
                    initializePeer()
                    createOffer()
                }
            }
            client.on("call:signal") { args ->
                val packet = args.firstOrNull() as? JSONObject ?: return@on
                runOnUiThread { handleSignal(packet) }
            }
            client.on("call:ended") {
                runOnUiThread {
                    status.text = "Call ended"
                    finishAfterTransition()
                }
            }
            client.on("call:rejected") {
                runOnUiThread {
                    status.text = "Call declined"
                    finishAfterTransition()
                }
            }
            client.on("call:error") { args ->
                val error = args.firstOrNull() as? JSONObject
                runOnUiThread { status.text = error?.optString("message") ?: "Call could not connect" }
            }
            client.connect()
        } catch (error: Exception) {
            status.text = error.localizedMessage ?: "Unable to connect call service"
        }
    }

    private fun acceptCall() {
        val client = socket
        if (client == null || !client.connected()) {
            status.text = "Reconnecting…"
            return
        }
        accepted = true
        acceptButton.visibility = View.GONE
        endButton.text = "End call"
        muteButton.visibility = View.VISIBLE
        speakerButton.visibility = View.VISIBLE
        cameraButton.visibility = if (videoCall) View.VISIBLE else View.GONE
        client.emit("call:accept", JSONObject().put("callId", callId))
        initializePeer()
        status.text = "Connecting to $peerName…"
    }

    private fun initializePeer() {
        if (peer != null) return
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
            )
            egl = EglBase.create()
            val encoder = DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true)
            val decoder = DefaultVideoDecoderFactory(egl!!.eglBaseContext)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoder)
                .setVideoDecoderFactory(decoder)
                .createPeerConnectionFactory()
            if (videoCall) setupVideo()
            audioSource = factory!!.createAudioSource(MediaConstraints())
            localAudio = factory!!.createAudioTrack("greencall-audio", audioSource)
            val ice = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val config = PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            peer = factory!!.createPeerConnection(config, observer) ?: throw IllegalStateException("Peer connection unavailable")
            peer?.addTrack(localAudio, listOf("greencall-stream"))
            if (videoCall) localVideo?.let { peer?.addTrack(it, listOf("greencall-stream")) }
        } catch (error: Exception) {
            status.text = "Could not start media: ${error.localizedMessage}"
        }
    }

    private fun setupVideo() {
        val base = egl ?: return
        remoteRenderer = SurfaceViewRenderer(this).apply {
            init(base.eglBaseContext, null)
            setMirror(false)
            setEnableHardwareScaler(true)
        }
        localRenderer = SurfaceViewRenderer(this).apply {
            init(base.eglBaseContext, null)
            setMirror(true)
            setEnableHardwareScaler(true)
        }
        root.addView(remoteRenderer, FrameLayout.LayoutParams(-1, -1))
        root.addView(localRenderer, FrameLayout.LayoutParams(320, 420, Gravity.TOP or Gravity.END).apply {
            topMargin = 20
            rightMargin = 18
        })
        val cameraEnumerator = Camera2Enumerator(this)
        val cameraName = cameraEnumerator.deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }
            ?: cameraEnumerator.deviceNames.firstOrNull()
        if (cameraName == null) {
            status.text = "No camera was found"
            return
        }
        capturer = cameraEnumerator.createCapturer(cameraName, null)
        videoSource = factory!!.createVideoSource(false)
        textureHelper = SurfaceTextureHelper.create("GreenCallCamera", base.eglBaseContext)
        capturer!!.initialize(textureHelper, this, videoSource!!.capturerObserver)
        capturer!!.startCapture(640, 480, 24)
        localVideo = factory!!.createVideoTrack("greencall-video", videoSource)
        localVideo?.addSink(localRenderer)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            runOnUiThread {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        connected = true
                        status.text = "Connected with $peerName"
                        if (incoming) {
                            muteButton.visibility = View.VISIBLE
                            speakerButton.visibility = View.VISIBLE
                            cameraButton.visibility = if (videoCall) View.VISIBLE else View.GONE
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> status.text = "Connection interrupted…"
                    PeerConnection.IceConnectionState.FAILED -> status.text = "Connection failed. Check network or try again."
                    else -> Unit
                }
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            socket?.emit("call:signal", JSONObject().put("callId", callId).put("type", "ice")
                .put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) {
            stream.videoTracks.firstOrNull()?.let { track ->
                remoteVideo = track
                remoteRenderer?.let { track.addSink(it) }
            }
        }
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) {
                remoteVideo = track
                runOnUiThread { remoteRenderer?.let { track.addSink(it) } }
            }
        }
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                remoteVideo = track
                runOnUiThread { remoteRenderer?.let { track.addSink(it) } }
            }
        }
    }

    private fun createOffer() {
        val connection = peer ?: return
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                connection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        socket?.emit("call:signal", JSONObject().put("callId", callId)
                            .put("type", "offer").put("sdp", desc.description))
                    }
                    override fun onSetFailure(error: String) { showError(error) }
                    override fun onCreateSuccess(p0: SessionDescription) = Unit
                    override fun onCreateFailure(p0: String) = Unit
                }, desc)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { showError(error) }
            override fun onSetFailure(error: String) { showError(error) }
        }, MediaConstraints())
    }

    private fun handleSignal(packet: JSONObject) {
        when (packet.optString("type")) {
            "offer" -> {
                if (peer == null) initializePeer()
                val desc = SessionDescription(SessionDescription.Type.OFFER, packet.optString("sdp"))
                peer?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        peer?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                peer?.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        socket?.emit("call:signal", JSONObject().put("callId", callId)
                                            .put("type", "answer").put("sdp", answer.description))
                                    }
                                    override fun onSetFailure(error: String) { showError(error) }
                                    override fun onCreateSuccess(p0: SessionDescription) = Unit
                                    override fun onCreateFailure(p0: String) = Unit
                                }, answer)
                            }
                            override fun onSetSuccess() = Unit
                            override fun onCreateFailure(error: String) { showError(error) }
                            override fun onSetFailure(error: String) { showError(error) }
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String) { showError(error) }
                    override fun onCreateSuccess(p0: SessionDescription) = Unit
                    override fun onCreateFailure(p0: String) = Unit
                }, desc)
            }
            "answer" -> {
                val desc = SessionDescription(SessionDescription.Type.ANSWER, packet.optString("sdp"))
                peer?.setRemoteDescription(simpleSdpObserver(), desc)
            }
            "ice" -> {
                val candidate = IceCandidate(packet.optString("sdpMid"), packet.optInt("sdpMLineIndex"), packet.optString("candidate"))
                peer?.addIceCandidate(candidate)
            }
        }
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(p0: String) = Unit
        override fun onSetFailure(error: String) { showError(error) }
    }

    private fun showError(message: String) { runOnUiThread { status.text = "Call error: $message" } }

    private fun endCall(notify: Boolean) {
        if (notify) socket?.emit("call:end", JSONObject().put("callId", callId))
        finish()
    }

    override fun onDestroy() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        localVideo?.dispose()
        localAudio?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        textureHelper?.dispose()
        remoteRenderer?.release()
        localRenderer?.release()
        peer?.close()
        peer?.dispose()
        factory?.dispose()
        egl?.release()
        runCatching { socket?.disconnect(); socket?.off(); socket?.close() }
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }
}
