package com.fm.digital.webrtc

import com.fm.digital.networking.SignalingClient
import com.fm.digital.networking.SignalingMessage
import com.fm.digital.ui.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.webrtc.*
import org.webrtc.RtpParameters as WebRtcRtpParameters

class MediasoupManager(
    private val webRtcEngine: WebRtcEngine,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope
) {
    private val tag = "MediasoupManager"
    private var sendTransport: PeerConnection? = null
    private var recvTransport: PeerConnection? = null

    private var sendTransportId: String? = null
    private var recvTransportId: String? = null

    private var pendingTransportDirection: String? = null
    private var pendingProducerIdToConsume: String? = null

    private var iceRestartAttempts = 0
    private val maxIceRestartAttempts = 3
    private var monitoringJob: Job? = null

    private val _producerId = MutableStateFlow<String?>(null)
    val producerId = _producerId.asStateFlow()

    private val _networkStats = MutableStateFlow("Latency: -- ms")
    val networkStats = _networkStats.asStateFlow()

    private val _inboundAudioLevel = MutableStateFlow(0)
    val inboundAudioLevel: StateFlow<Int> = _inboundAudioLevel.asStateFlow()

    private var audioLevelAnalysisJob: Job? = null

    private var remoteAudioTrack: AudioTrack? = null
    private var isPlaybackEnabled = true

    init {
        observeSignalingEvents()
    }

    fun start() {
        Logger.i("$tag: Requesting router capabilities")
        signalingClient.send("getRouterRtpCapabilities", SignalingMessage.GetRouterRtpCapabilities)
    }

    private fun observeSignalingEvents() {
        signalingClient.messages
            .onEach { message ->
                when (message) {
                    is SignalingMessage.RouterRtpCapabilities -> {
                        Logger.i("$tag: Received router capabilities")
                        createSendTransport()
                    }
                    is SignalingMessage.TransportCreated -> {
                        Logger.i("$tag: Transport created: ${message.id}")
                        onTransportCreated(message)
                    }
                    is SignalingMessage.TransportConnected -> {
                        Logger.i("$tag: Transport connected: ${message.transportId}")
                        if (message.transportId == sendTransportId) {
                            produceAudio(message.transportId)
                            startStatsMonitoring()
                        }
                    }
                    is SignalingMessage.Produced -> {
                        Logger.i("$tag: Audio produced with id: ${message.id}")
                        _producerId.value = message.id
                    }
                    is SignalingMessage.NewProducer -> {
                        Logger.i("$tag: New producer discovered: ${message.producerId} from ${message.peerId}")
                        if (message.kind == "audio") {
                            consumeRemoteProducer(message.producerId)
                        }
                    }
                    is SignalingMessage.Consumed -> {
                        Logger.log("$tag: Server says Consume this: ${message.id}")
                        handleConsumed(message)
                    }
                    is SignalingMessage.ProducerClosed -> {
                        Logger.i("$tag: Producer closed: ${message.producerId}")
                    }
                    else -> {
                        Logger.w("$tag: Received unhandled message: ${message::class.simpleName}")
                    }
                }
            }
            .launchIn(scope)
    }

    private fun createSendTransport() {
        Logger.i("$tag: Creating send transport")
        pendingTransportDirection = "send"
        signalingClient.send("createTransport", SignalingMessage.CreateTransport(direction = "send"))
    }

    fun createRecvTransport() {
        Logger.log("$tag: Step 1 - Requesting Recv Transport")
        pendingTransportDirection = "recv"
        signalingClient.send("createTransport", SignalingMessage.CreateTransport(direction = "recv"))
    }

    private fun consumeRemoteProducer(producerId: String) {
        if (recvTransport == null) {
            pendingProducerIdToConsume = producerId
            createRecvTransport()
        } else {
            signalingClient.send("consume", SignalingMessage.Consume(producerId))
        }
    }

    private fun handleConsumed(message: SignalingMessage.Consumed) {
        Logger.log("$tag: Resuming consumer: ${message.id}")
        signalingClient.send("resumeConsumer", SignalingMessage.ResumeConsumer(message.id))
    }

    private fun onTransportCreated(params: SignalingMessage.TransportCreated) {
        val direction = pendingTransportDirection
        pendingTransportDirection = null

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            iceServers = emptyList()
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Logger.i("$tag [$direction] ICE State: $state")
                if (direction == "send") {
                    when (state) {
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> handleConnectionFailure()
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Logger.i("$tag: Audio Pipe Fully Open")
                            iceRestartAttempts = 0
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Logger.i("$tag [$direction]: New local ICE candidate: ${candidate.sdp}")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                if (direction == "recv") {
                    val track = receiver?.track() as? AudioTrack
                    track?.let {
                        Logger.log("$tag: Studio Audio Track Received. Enabling Playback.")
                        remoteAudioTrack = it
                        it.setEnabled(isPlaybackEnabled)
                        it.setVolume(if (isPlaybackEnabled) 1.0 else 0.0)
                        
                        // Start monitoring inbound audio levels for VU meter
                        startAudioLevelAnalysis()
                    }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }

        val pc = webRtcEngine.createPeerConnection(rtcConfig, observer)
        if (direction == "recv") {
            recvTransport = pc
            recvTransportId = params.id
            Logger.log("$tag: Recv Transport Object Created locally")
            
            pendingProducerIdToConsume?.let {
                signalingClient.send("consume", SignalingMessage.Consume(it))
                pendingProducerIdToConsume = null
            }
        } else {
            sendTransport = pc
            sendTransportId = params.id
        }

        signalingClient.send("connectTransport", SignalingMessage.ConnectTransport(
            transportId = params.id,
            dtlsParameters = params.dtlsParameters
        ))
    }

    private fun produceAudio(transportId: String) {
        val audioTrack = webRtcEngine.startLocalAudio()
        if (audioTrack != null && sendTransport != null) {
            val init = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf("mic-stream")
            )

            val transceiver = sendTransport?.addTransceiver(audioTrack, init)

            val parameters = transceiver?.sender?.parameters
            parameters?.codecs?.forEach { codec ->
                if (codec.name.equals("opus", ignoreCase = true)) {
                    codec.parameters["sprop-stereo"] = "1"
                    codec.parameters["usedtx"] = "0"
                    codec.parameters["useinbandfec"] = "1"
                    codec.parameters["maxaveragebitrate"] = "128000"
                }
            }
            transceiver?.sender?.parameters = parameters

            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    handleSdp(sdp, transportId, transceiver)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) { Logger.e("$tag: Create offer failed: $error") }
                override fun onSetFailure(error: String) { Logger.e("$tag: Set failure: $error") }
            }

            val mediaConstraints = MediaConstraints()
            sendTransport?.createOffer(observer, mediaConstraints)
        } else {
            Logger.w("$tag: Can't produce audio — audioTrack or sendTransport is null")
        }
    }

    private fun handleSdp(sdp: SessionDescription?, transportId: String, transceiver: RtpTransceiver?) {
        sdp ?: return
        val mungedSdp = mungeSdpForStudio(sdp)
        sendTransport?.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                val rtpParameters = transceiver?.sender?.parameters
                if (rtpParameters != null) {
                    val rtpParametersJson = rtpParametersToJSON(rtpParameters)
                    val produceMessage = SignalingMessage.Produce(
                        transportId = transportId,
                        kind = "audio",
                        rtpParameters = rtpParametersJson
                    )
                    signalingClient.send("produce", produceMessage)
                }
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
        }, mungedSdp)
    }

    private fun mungeSdpForStudio(sdp: SessionDescription): SessionDescription {
        val lines = sdp.description.split(Regex("\r?\n")).toMutableList()
        val newLines = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty()) continue
            newLines.add(line)
            if (line.contains("a=rtpmap") && line.contains("opus/48000")) {
                val payloadType = line.substringAfter("a=rtpmap:").substringBefore(" ")
                val fmtpLine = "a=fmtp:$payloadType useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000"
                val nextHasFmtp = lines.dropWhile { it != line }.drop(1).any { it.startsWith("a=fmtp:$payloadType") }
                if (!nextHasFmtp) {
                    newLines.add(fmtpLine)
                }
            }
        }
        return SessionDescription(sdp.type, newLines.joinToString("\r\n"))
    }

    private fun rtpParametersToJSON(rtpParameters: WebRtcRtpParameters): JsonObject {
        return buildJsonObject {
            put("codecs", buildJsonArray {
                rtpParameters.codecs.forEach { codec ->
                    add(buildJsonObject {
                        val mimeType = if (codec.name.equals("opus", ignoreCase = true)) "audio/opus" else "audio/${codec.name}"
                        put("mimeType", JsonPrimitive(mimeType))
                        put("payloadType", JsonPrimitive(codec.payloadType))
                        put("clockRate", JsonPrimitive(codec.clockRate))
                        codec.numChannels?.let { put("channels", JsonPrimitive(it)) }
                        put("parameters", buildJsonObject {
                            codec.parameters.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                        })
                    })
                }
            })
            put("headerExtensions", buildJsonArray {
                rtpParameters.headerExtensions.forEach { ext ->
                    add(buildJsonObject {
                        put("uri", JsonPrimitive(ext.uri))
                        put("id", JsonPrimitive(ext.id))
                    })
                }
            })
            put("encodings", buildJsonArray {
                rtpParameters.encodings.forEach { encoding ->
                    add(buildJsonObject {
                        encoding.ssrc?.let { put("ssrc", JsonPrimitive(it)) }
                        put("active", JsonPrimitive(encoding.active))
                    })
                }
            })
            rtpParameters.rtcp?.let { rtcp ->
                put("rtcp", buildJsonObject {
                    put("cname", JsonPrimitive(rtcp.cname))
                    put("reducedSize", JsonPrimitive(rtcp.reducedSize))
                })
            }
        }
    }

    private fun startAudioLevelAnalysis() {
        audioLevelAnalysisJob?.cancel()
        audioLevelAnalysisJob = scope.launch {
            while (isActive) {
                try {
                    remoteAudioTrack?.let { track ->
                        recvTransport?.getStats { reports ->
                            val inboundStats = reports.statsMap.values.find { 
                                it.type == "inbound-rtp" && 
                                it.members.containsKey("audioLevel")
                            }
                            
                            val audioLevel = inboundStats?.members?.get("audioLevel") as? Double ?: 0.0
                            val level = (audioLevel * 100).coerceIn(0.0, 100.0).toInt()
                            _inboundAudioLevel.value = level
                        }
                    }
                } catch (t: Throwable) {
                    Logger.w("$tag: Error analyzing audio levels: ${t.message}")
                }
                delay(100) // Update every 100ms for responsive VU meter
            }
        }
    }

    private fun stopAudioLevelAnalysis() {
        audioLevelAnalysisJob?.cancel()
        audioLevelAnalysisJob = null
    }

    private fun startStatsMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    // Monitoring Outbound Network
                    sendTransport?.getStats { reports ->
                        reports.statsMap.values.forEach { report ->
                            if (report.type == "remote-inbound-rtp") {
                                val rtt = (report.members["roundTripTime"] as? Number)?.toDouble() ?: 0.0
                                val lost = (report.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                Logger.i("Network [SEND]: RTT ${ (rtt * 1000).toInt() }ms | Lost: $lost")
                            }
                        }
                    }
                    
                    // Monitoring Inbound Network (Mix-Minus)
                    recvTransport?.getStats { reports ->
                        val rtpStats = reports.statsMap.values.find { it.type == "remote-inbound-rtp" }
                        val rtt = rtpStats?.members?.get("roundTripTime") as? Double ?: 0.0
                        val rttMs = (rtt * 1000).toInt()
                        _networkStats.value = "Latency: ${rttMs}ms"
                        
                        if (rttMs > 200) Logger.w("WARNING: High Latency in Studio Feed: ${rttMs}ms")
                    }
                } catch (_: Throwable) {}
                delay(3000)
            }
        }
    }

    private fun handleConnectionFailure() {
        val transportId = sendTransportId
        if (!transportId.isNullOrEmpty() && iceRestartAttempts < maxIceRestartAttempts) {
            iceRestartAttempts++
            Logger.i("$tag: Attempting ICE restart (attempt $iceRestartAttempts/$maxIceRestartAttempts)")
            signalingClient.send("restartIce", SignalingMessage.RestartIce(transportId))
            scope.launch {
                delay(4000)
                if (iceRestartAttempts >= maxIceRestartAttempts) {
                    recreateSendTransport()
                }
            }
        } else {
            recreateSendTransport()
        }
    }

    private fun recreateSendTransport() {
        Logger.i("$tag: Recreating send transport")
        try {
            sendTransport?.dispose()
        } catch (_: Throwable) {} finally {
            sendTransport = null
            sendTransportId = null
            iceRestartAttempts = 0
        }
        scope.launch {
            delay(2000)
            createSendTransport()
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        webRtcEngine.getLocalAudioTrack()?.setEnabled(enabled)
        Logger.log("Mic state changed: $enabled")
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        isPlaybackEnabled = enabled
        remoteAudioTrack?.let {
            it.setEnabled(enabled)
            it.setVolume(if (enabled) 1.0 else 0.0)
        }
        Logger.log("Studio Monitor state changed: $enabled")
    }

    fun close() {
        Logger.i("$tag: Closing...")
        _producerId.value = null
        
        // Stop audio level analysis
        stopAudioLevelAnalysis()
        
        monitoringJob?.cancel()
        monitoringJob = null
        try {
            sendTransport?.dispose()
            recvTransport?.dispose()
        } catch (_: Throwable) {} finally {
            sendTransport = null
            recvTransport = null
            sendTransportId = null
            recvTransportId = null
        }
        webRtcEngine.release()
    }
}
