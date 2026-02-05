package com.fm.digital.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fm.digital.networking.ConnectionState
import com.fm.digital.networking.SignalingClient
import com.fm.digital.networking.SignalingClientImpl
import com.fm.digital.networking.SignalingMessage
import com.fm.digital.webrtc.WebRtcEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.webrtc.*

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<SignalingMessage>()
    val messages: SharedFlow<SignalingMessage> = _messages

    private var signalingClient: SignalingClient? = null
    private var webRtcEngine: WebRtcEngine? = null
    private var sendTransport: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    private var transportId: String? = null
    private var audioTransceiver: RtpTransceiver? = null

    private var currentServerUrl: String? = null
    private var currentRoomId: String? = null
    private var currentPeerId: String? = null

    private var iceReconnectionAttempts = 0
    private val MAX_ICE_RECONNECTION_ATTEMPTS = 5

    fun connect(serverUrl: String, roomId: String, peerId: String) {
        Logger.log("Connection [Start] server=$serverUrl, room=$roomId, peer=$peerId")

        // Store connection parameters for reconnection
        currentServerUrl = serverUrl
        currentRoomId = roomId
        currentPeerId = peerId
        iceReconnectionAttempts = 0

        disconnect()

        webRtcEngine = WebRtcEngine(getApplication())
        localAudioTrack = webRtcEngine?.startLocalAudio()

        val client = SignalingClientImpl(serverUrl, viewModelScope)
        signalingClient = client
        client.connect()

        viewModelScope.launch {
            client.connectionState.collect { state ->
                _connectionState.value = state
                if (state == ConnectionState.CONNECTED) {
                    Logger.log("WS Connected: Automatically joining room $roomId")
                    joinRoom(roomId, peerId)
                }
            }
        }

        viewModelScope.launch {
            client.messages.collect { message ->
                _messages.emit(message)
                handleMessage(message)
            }
        }
    }

    private fun joinRoom(roomId: String, peerId: String) {
        signalingClient?.send("join", SignalingMessage.Join(roomId, peerId))
    }

    private fun handleMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Joined -> {
                Logger.log("Joined: request caps")
                signalingClient?.send("getRouterRtpCapabilities", SignalingMessage.GetRouterRtpCapabilities)
            }
            is SignalingMessage.RouterRtpCapabilities -> {
                Logger.log("Caps received: create send transport")
                signalingClient?.send("createTransport", SignalingMessage.CreateTransport(direction = "send"))
            }
            is SignalingMessage.TransportCreated -> {
                Logger.log("Transport Created: ${message.id}")
                transportId = message.id
                setupSendTransport(message)
            }
            is SignalingMessage.TransportConnected -> {
                Logger.log("Transport Connected: ${message.transportId}")
                sendProduce(message.transportId)

            }
            is SignalingMessage.Produced -> {
                Logger.log("Produced successfully: ${message.id}")
                message.answer?.let { sdpAnswer ->
                    handleSdpAnswer(sdpAnswer)
                }
            }
            is SignalingMessage.Error -> {
                Logger.log("Error from server: ${message.error}")
                _connectionState.value = ConnectionState.ERROR
                attemptReconnection()
            }
            else -> {}
        }
    }

    private fun handleSdpAnswer(sdpAnswer: String) {
        if (sdpAnswer.isBlank()) return

        try {
            val lines = sdpAnswer.split("\r\n", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val normalizedSdp = lines.joinToString(separator = "\r\n", postfix = "\r\n")
            Logger.log("SDP [Answer] Lines=${lines.size}, Length=${normalizedSdp.length}")

            val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, normalizedSdp)

            sendTransport?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Logger.log("SDP [Remote Description] SUCCESS")
                    startStatsMonitoring()
                    iceReconnectionAttempts = 0 // Reset on success
                }
                override fun onSetFailure(err: String?) {
                    Logger.log("SDP [Remote Description] FAILURE: $err")
                    _connectionState.value = ConnectionState.ERROR
                }
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onCreateFailure(err: String?) {}
            }, remoteDesc)
        } catch (e: Exception) {
            Logger.log("SDP [Exception]: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun setupSendTransport(params: SignalingMessage.TransportCreated) {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL

        sendTransport = webRtcEngine?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Logger.log("PC [ICE Candidate] ${candidate.sdpMid}")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Logger.log("PC [Signaling] $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Logger.log("PC [ICE Connection] $state")
                when (state) {
                    PeerConnection.IceConnectionState.FAILED -> {
                        Logger.log("ICE FAILED - Attempting reconnection")
                        _connectionState.value = ConnectionState.ERROR
                        handleIceFailure()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Logger.log("ICE DISCONNECTED - Monitoring for recovery")
                        viewModelScope.launch {
                            delay(5000) // Wait 5s for automatic recovery
                            if (sendTransport?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                                Logger.log("ICE still disconnected after 5s - triggering reconnection")
                                handleIceFailure()
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Logger.log("ICE CONNECTED/COMPLETED")
                        iceReconnectionAttempts = 0
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Logger.log("PC [ICE Receiving] $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Logger.log("PC [ICE Gathering] $state")
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}

            override fun onRenegotiationNeeded() {
                Logger.log("PC [Renegotiation Needed] - This should not happen in send-only mode")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })

        val track = localAudioTrack ?: run {
            Logger.log("setupSendTransport: localAudioTrack is NULL")
            return
        }

        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        audioTransceiver = sendTransport?.addTransceiver(track, init)

        // Set Opus codec preference
        val capabilities = webRtcEngine?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
        val opusCodec = capabilities?.codecs?.find { it.name.equals("opus", ignoreCase = true) }

        if (opusCodec != null) {
            try {
                audioTransceiver?.setCodecPreferences(listOf(opusCodec))
                Logger.log("RTP [Codec] Opus preferred")
            } catch (e: Exception) {
                Logger.log("RTP [Warning] Could not set codec preferences: ${e.message}")
            }
        }

        createOfferAndConnect(params)
    }

    private fun createOfferAndConnect(params: SignalingMessage.TransportCreated) {
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))

        sendTransport?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                Logger.log("SDP [Offer Created]")

                var sdp = desc.description

                // Ensure a=setup:actpass is present
                if (!sdp.contains("a=setup:")) {
                    sdp = sdp.replace("a=mid:0", "a=setup:actpass\r\na=mid:0")
                } else {
                    sdp = sdp.replace("a=setup:active", "a=setup:actpass")
                    sdp = sdp.replace("a=setup:passive", "a=setup:actpass")
                }

                val modifiedDesc = SessionDescription(desc.type, sdp)

                sendTransport?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Logger.log("SDP [Local Description Set]")
                        val dtlsParameters = extractDtlsParameters(sdp)

                        val dtlsJson = buildJsonObject {
                            put("role", "client")
                            put("fingerprints", buildJsonArray {
                                dtlsParameters.fingerprints.forEach { f ->
                                    add(buildJsonObject {
                                        put("algorithm", f.algorithm)
                                        put("value", f.value)
                                    })
                                }
                            })
                        }

                        Logger.log("DTLS [Connecting] ${dtlsJson.toString()}")
                        signalingClient?.send("connectTransport",
                            SignalingMessage.ConnectTransport(params.id, dtlsJson))
                    }
                    override fun onSetFailure(err: String?) {
                        Logger.log("SDP [Local Set] FAILURE: $err")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, modifiedDesc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Logger.log("SDP [Offer Create] FAILURE: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, mediaConstraints)
    }

    private fun sendProduce(transportId: String) {
        val sender = audioTransceiver?.sender

        val rtpJson = if (sender == null || sender.parameters.codecs.isEmpty()) {
            Logger.log("RTP [Using Fallback] Sender has no codecs yet")
            buildFallbackRtpParameters(transportId)
        } else {
            Logger.log("RTP [Using Sender] Has ${sender.parameters.codecs.size} codecs")
            buildRtpParametersFromSender(sender.parameters, transportId)
        }

        Logger.log("RTP [Produce] Sending parameters")
        signalingClient?.send("produce", SignalingMessage.Produce(transportId, "audio", rtpJson))
    }

    private fun buildFallbackRtpParameters(transportId: String): JsonObject {
        val capabilities = webRtcEngine?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)

        return buildJsonObject {
            put("mid", "0")
            put("codecs", buildJsonArray {
                capabilities?.codecs?.filter { it.name.equals("opus", ignoreCase = true) }?.forEach { codec ->
                    add(buildJsonObject {
                        put("mimeType", codec.mimeType)
                        put("payloadType", codec.preferredPayloadType)
                        put("clockRate", codec.clockRate)
                        codec.numChannels?.let { put("channels", it) }
                        put("parameters", buildJsonObject {
                            codec.parameters?.forEach { (key, value) -> put(key, value) }
                            put("useinbandfec", "1")
                            put("minptime", "10")
                        })
                    })
                }
            })
            put("headerExtensions", buildJsonArray {
                capabilities?.headerExtensions?.forEach { ext ->
                    add(buildJsonObject {
                        put("id", ext.preferredId)
                        put("uri", ext.uri)
                    })
                }
            })
            put("encodings", buildJsonArray {
                add(buildJsonObject {
                    // Get actual SSRC from the transceiver's sender
                    audioTransceiver?.sender?.parameters?.encodings?.firstOrNull()?.ssrc?.let {
                        put("ssrc", it.toLong())
                    }
                    put("active", true)
                    put("maxBitrate", 128000)
                })
            })
            put("rtcp", buildJsonObject {
                put("cname", "${transportId}@fm-digital")
                put("reducedSize", true)
            })
        }
    }

    private fun buildRtpParametersFromSender(params: RtpParameters, transportId: String): JsonObject {
        return buildJsonObject {
            put("mid", "0")
            put("codecs", buildJsonArray {
                val opusCodec = params.codecs.find { it.name.equals("opus", ignoreCase = true) }
                if (opusCodec != null) {
                    add(buildJsonObject {
                        put("mimeType", "audio/opus")
                        put("payloadType", opusCodec.payloadType)
                        put("clockRate", opusCodec.clockRate)
                        opusCodec.numChannels?.let { put("channels", it) }
                        put("parameters", buildJsonObject {
                            opusCodec.parameters?.forEach { (key, value) ->
                                when (value) {
                                    is String -> put(key, value)
                                }
                            }
                        })
                    })
                } else {
                    Logger.log("RTP [ERROR] No Opus codec found in sender parameters!")
                }
            })
            put("headerExtensions", buildJsonArray {
                params.headerExtensions.forEach { ext ->
                    add(buildJsonObject {
                        put("id", ext.id)
                        put("uri", ext.uri)
                    })
                }
            })
            put("encodings", buildJsonArray {
                params.encodings.forEach { encoding ->
                    add(buildJsonObject {
                        encoding.ssrc?.let { put("ssrc", it.toLong()) }
                        put("active", encoding.active)
                    })
                }
            })
            put("rtcp", buildJsonObject {
                put("cname", "$transportId@fm-digital")
                put("reducedSize", true)
            })
        }
    }

    private data class DtlsFingerprint(val algorithm: String, val value: String)
    private data class DtlsParameters(val role: String, val fingerprints: List<DtlsFingerprint>)

    private fun extractDtlsParameters(sdp: String): DtlsParameters {
        var setupRole = "auto"
        val fingerprints = mutableListOf<DtlsFingerprint>()

        sdp.lines().forEach { line ->
            val trimmed = line.trim()

            if (trimmed.startsWith("a=setup:", ignoreCase = true)) {
                val role = trimmed.substringAfter("setup:", "").trim().lowercase()
                setupRole = when (role) {
                    "active" -> "client"
                    "passive" -> "server"
                    "actpass" -> "auto"
                    else -> "auto"
                }
            } else if (trimmed.startsWith("a=fingerprint:", ignoreCase = true)) {
                val parts = trimmed.substringAfter("fingerprint:", "").trim().split(" ", limit = 2)
                if (parts.size == 2) {
                    val algorithm = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                        .replace(":", "")
                        .uppercase()
                        .chunked(2)
                        .joinToString(":")
                    fingerprints.add(DtlsFingerprint(algorithm, value))
                }
            }
        }

        return DtlsParameters(setupRole, fingerprints)
    }

    private var statsTimer: java.util.Timer? = null

    private fun startStatsMonitoring() {
        statsTimer?.cancel()
        statsTimer = java.util.Timer("RTPStats", true)
        statsTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() { checkRtpStats() }
        }, 2000, 5000)
    }

    private fun checkRtpStats() {
        val pc = sendTransport ?: return
        pc.getStats { statsReport ->
            statsReport.statsMap.values.filter { it.type == "outbound-rtp" }.forEach { stats ->
                val packets = (stats.members["packetsSent"] as? Number)?.toLong() ?: 0L
                val bytes = (stats.members["bytesSent"] as? Number)?.toLong() ?: 0L
                Logger.log("Stats [RTP] packets=$packets, bytes=$bytes, ice=${pc.iceConnectionState()}")

                // Health check: if packets stopped increasing, connection may be dead
                // You can add logic here to track previous packet count
            }
        }
    }

    private fun stopStatsMonitoring() {
        statsTimer?.cancel()
        statsTimer = null
    }

    private fun handleIceFailure() {
        if (iceReconnectionAttempts >= MAX_ICE_RECONNECTION_ATTEMPTS) {
            Logger.log("ICE [Max Reconnection Attempts] Giving up after $iceReconnectionAttempts attempts")
            disconnect()
            return
        }

        iceReconnectionAttempts++
        Logger.log("ICE [Reconnection] Attempt $iceReconnectionAttempts of $MAX_ICE_RECONNECTION_ATTEMPTS")

        viewModelScope.launch {
            val delayMs = (2000 * iceReconnectionAttempts).toLong() // Progressive backoff
            delay(delayMs)
            attemptReconnection()
        }
    }

    private fun attemptReconnection() {
        val url = currentServerUrl
        val room = currentRoomId
        val peer = currentPeerId

        if (url != null && room != null && peer != null) {
            Logger.log("Reconnection [Full] Starting with stored credentials")
            connect(url, room, peer)
        } else {
            Logger.log("Reconnection [Failed] No stored credentials")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnect() {
        Logger.log("Disconnect [Cleanup]")
        stopStatsMonitoring()
        signalingClient?.disconnect()
        sendTransport?.dispose()
        sendTransport = null
        audioTransceiver = null
        webRtcEngine?.release()
        webRtcEngine = null
        localAudioTrack = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}