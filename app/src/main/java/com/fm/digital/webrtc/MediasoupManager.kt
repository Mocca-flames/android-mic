package com.fm.digital.webrtc

import com.fm.digital.networking.SignalingClient
import com.fm.digital.networking.SignalingMessage
import com.fm.digital.ui.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    // store current transport id so we can request ICE restart
    private var currentTransportId: String? = null

    // basic reconnect bookkeeping
    private var iceRestartAttempts = 0
    private val maxIceRestartAttempts = 3

    // monitoring job reference so we can cancel it on close()
    private var monitoringJob: Job? = null

    private val _producerId = MutableStateFlow<String?>(null)
    val producerId = _producerId.asStateFlow()

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
                        produceAudio(message.transportId)
                        startStatsMonitoring()
                    }
                    is SignalingMessage.Produced -> {
                        Logger.i("$tag: Audio produced with id: ${message.id}")
                        _producerId.value = message.id
                    }
                    else -> {
                        Logger.w("$tag: Received unhandled message: ${message::class.simpleName}")
                    }
                }
            }
            .launchIn(scope)
    }

    private fun onTransportCreated(params: SignalingMessage.TransportCreated) {
        currentTransportId = params.id

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            // Keep iceServers empty if you rely on server-lite ICE.
            iceServers = emptyList()
            // Optionally set other RTCConfiguration options here (sdpSemantics, continualGatheringPolicy, etc.)
        }

        sendTransport = webRtcEngine.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Logger.i("$tag: ICE Connection State: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> {
                        // Try ICE restart first; if that fails repeatedly we recreate the transport.
                        handleConnectionFailure()
                    }
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Logger.i("$tag: Audio Pipe Fully Open")
                        // reset attempts on success
                        iceRestartAttempts = 0
                    }
                    else -> {
                        // NO-OP for other states
                    }
                }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                candidate.let {
                    Logger.i("$tag: New local ICE candidate: ${it.sdp}")
                    // If your server requires candidates, send them:
                    // signalingClient.send("addIceCandidate", SignalingMessage.AddIceCandidate(...))
                }
            }

            // Keep other overrides empty or for logging
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {
                Logger.i("$tag: Renegotiation Needed (ICE Restart point)")
            }
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        val connectTransportMessage = SignalingMessage.ConnectTransport(
            transportId = params.id,
            dtlsParameters = params.dtlsParameters
        )
        signalingClient.send("connectTransport", connectTransportMessage)
    }

    private fun createSendTransport() {
        Logger.i("$tag: Creating send transport")
        signalingClient.send("createTransport", SignalingMessage.CreateTransport(direction = "send"))
    }

    private fun produceAudio(transportId: String) {
        val audioTrack = webRtcEngine.startLocalAudio()
        if (audioTrack != null && sendTransport != null) {
            val init = RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf("mic-stream")
            )

            val transceiver = sendTransport?.addTransceiver(audioTrack, init)

            // Optimization for FM Studio Quality - be mindful some fields may not be supported by every platform
            val parameters = transceiver?.sender?.parameters
            parameters?.codecs?.forEach { codec ->
                if (codec.name.equals("opus", ignoreCase = true)) {
                    // codec.parameters is usually a MutableMap<String, String>, but check your SDK version
                    codec.parameters["sprop-stereo"] = "1"
                    codec.parameters["usedtx"] = "0" // Disable DTX to prevent audio clipping during silence
                    codec.parameters["useinbandfec"] = "1" // Enable FEC for network jitters
                    codec.parameters["maxaveragebitrate"] = "128000" // High quality studio bitrate
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

    private fun mungeSdpForStudio(sdp: SessionDescription): SessionDescription {
        // Properly split SDP into lines and rejoin using CRLF.
        val lines = sdp.description.split(Regex("\r?\n")).toMutableList()
        val newLines = mutableListOf<String>()

        for (line in lines) {
            if (line.isEmpty()) continue
            newLines.add(line)
            // Find the Opus codec line and inject our fmtp parameters right after it
            if (line.contains("a=rtpmap") && line.contains("opus/48000")) {
                val payloadType = line.substringAfter("a=rtpmap:").substringBefore(" ")
                // Append an a=fmtp line for the payload type if not already present
                val fmtpLine = "a=fmtp:$payloadType useinbandfec=1;usedtx=0;stereo=1;sprop-stereo=1;maxaveragebitrate=128000"
                // Avoid duplicating fmtp if it already exists later
                // (simple check: ensure next lines don't already start with a=fmtp:payloadType)
                val nextHasFmtp = lines.dropWhile { it != line }.drop(1).any { it.startsWith("a=fmtp:$payloadType") }
                if (!nextHasFmtp) {
                    newLines.add(fmtpLine)
                }
            }
        }

        val mungedDescription = newLines.joinToString("") + ""
        return SessionDescription(sdp.type, mungedDescription)
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
                    Logger.d("$tag: Sending produce with rtpParameters: $rtpParametersJson")

                    val produceMessage = SignalingMessage.Produce(
                        transportId = transportId,
                        kind = "audio",
                        rtpParameters = rtpParametersJson
                    )
                    signalingClient.send("produce", produceMessage)
                } else {
                    Logger.e("$tag: Failed to get RTP parameters")
                }
            }

            override fun onCreateFailure(error: String) { Logger.e("$tag: Local SDP create failure: $error") }
            override fun onSetFailure(error: String) { Logger.e("$tag: Set local description failed: $error") }
        }, mungedSdp)
    }

    private fun rtpParametersToJSON(rtpParameters: WebRtcRtpParameters): JsonObject {
        return buildJsonObject {
            put("codecs", buildJsonArray {
                rtpParameters.codecs.forEach { codec ->
                    add(buildJsonObject {
                        val mimeType = if (codec.name.equals("opus", ignoreCase = true)) {
                            "audio/opus"
                        } else {
                            "audio/${codec.name}"
                        }

                        put("mimeType", JsonPrimitive(mimeType))
                        put("payloadType", JsonPrimitive(codec.payloadType))
                        put("clockRate", JsonPrimitive(codec.clockRate))
                        codec.numChannels?.let { put("channels", JsonPrimitive(it)) }
                        put("parameters", buildJsonObject {
                            codec.parameters.forEach { (key, value) ->
                                put(key, JsonPrimitive(value))
                            }
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

    private fun startStatsMonitoring() {
        // Cancel previous job if any
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    sendTransport?.getStats { reports ->
                        reports.statsMap.values.forEach { report ->
                            if (report.type == "remote-inbound-rtp") {
                                val rttNumber = report.members["roundTripTime"] as? Number
                                val roundTripTime = rttNumber?.toDouble() ?: 0.0
                                val packetsLostNumber = report.members["packetsLost"] as? Number
                                val packetsLost = packetsLostNumber?.toLong() ?: 0L
                                Logger.i("Network: RTT ${ (roundTripTime * 1000).toInt() }ms | Lost: $packetsLost")

                                // If RTT is too high, we may need to alert the UI or schedule bitrate changes
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Logger.e("$tag: Stats monitoring error: ${t.message}")
                }
                delay(2000) // Check every 2 seconds
            }
        }
    }

    private fun handleConnectionFailure() {
        Logger.w("$tag: ICE connection lost — attempting recovery")
    
        // 1) Try ICE restart several times first, if we have a transport id and attempts < max
        val transportId = currentTransportId
        if (!transportId.isNullOrEmpty() && iceRestartAttempts < maxIceRestartAttempts) {
            iceRestartAttempts += 1
            Logger.i("$tag: Attempting ICE restart (attempt $iceRestartAttempts/$maxIceRestartAttempts)")
            restartIce(transportId)
            // Give ICE restart some time — do not dispose transport immediately. If ICE restart does not
            // succeed, the ICE state change callback will call handleConnectionFailure again and we will
            // escalate to recreate transport after attempts are exhausted.
            scope.launch {
                // Wait a little then check state; if still disconnected after some time, escalate.
                delay(4000)
                // If still no transport or still disconnected, fall through to recreate
                if (iceRestartAttempts >= maxIceRestartAttempts) {
                    Logger.w("$tag: ICE restart attempts exhausted, recreating transport")
                    recreateTransport()
                }
            }
        } else {
            // Otherwise recreate transport
            recreateTransport()
        }
    }

    private fun recreateTransport() {
        Logger.i("$tag: Recreating send transport (full recreate)")
        try {
            sendTransport?.dispose()
        } catch (t: Throwable) {
            Logger.e("$tag: Error disposing sendTransport: ${t.message}")
        } finally {
            sendTransport = null
            currentTransportId = null
            iceRestartAttempts = 0
        }

        scope.launch {
            delay(2000) // small backoff
            createSendTransport()
        }
    }

    private fun restartIce(transportId: String) {
        Logger.i("$tag: Restarting ICE for transport: $transportId")
        val message = SignalingMessage.RestartIce(transportId)
        signalingClient.send("restartIce", message)
    }

    fun setMicEnabled(enabled: Boolean) {
        webRtcEngine.getLocalAudioTrack()?.setEnabled(enabled)
        Logger.log("Mic state changed: $enabled")
    }

    fun close() {
        Logger.i("$tag: Closing...")
        _producerId.value = null
        monitoringJob?.cancel()
        monitoringJob = null
        try {
            sendTransport?.dispose()
        } catch (t: Throwable) {
            Logger.e("$tag: Error disposing sendTransport on close: ${t.message}")
        } finally {
            sendTransport = null
            currentTransportId = null
        }
        webRtcEngine.release()
    }
}