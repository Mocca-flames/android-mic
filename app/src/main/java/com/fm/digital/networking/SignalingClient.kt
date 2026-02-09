package com.fm.digital.networking

import com.fm.digital.ui.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.pow

interface SignalingClient {
    val connectionState: StateFlow<ConnectionState>
    val messages: SharedFlow<SignalingMessage>

    fun connect()
    fun disconnect()
    fun send(method: String, message: SignalingMessage)
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class SignalingClientImpl(
    private val serverUrl: String,
    private val scope: CoroutineScope
) : SignalingClient, WebSocketListener() {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val messageQueue = ConcurrentLinkedQueue<Pair<String, JsonObject>>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<SignalingMessage>()
    override val messages: SharedFlow<SignalingMessage> = _messages

    private var reconnectionAttempts = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    override fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) return

        _connectionState.value = ConnectionState.CONNECTING
        Logger.i("WS [Connecting] $serverUrl")

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, this)
    }

    override fun disconnect() {
        Logger.i("WS [Disconnecting]")
        webSocket?.close(1000, "Client disconnect")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun send(method: String, message: SignalingMessage) {
        val messageJson = when (message) {
            is SignalingMessage.Join -> buildJsonObject {
                put("roomId", message.roomId)
                put("peerId", message.peerId)
            }
            is SignalingMessage.GetRouterRtpCapabilities -> JsonObject(emptyMap())
            is SignalingMessage.CreateTransport -> buildJsonObject {
                put("direction", message.direction)
            }
            is SignalingMessage.ConnectTransport -> buildJsonObject {
                put("transportId", message.transportId)
                put("dtlsParameters", message.dtlsParameters)
            }
            is SignalingMessage.Produce -> buildJsonObject {
                put("transportId", message.transportId)
                put("kind", message.kind)
                put("rtpParameters", message.rtpParameters)
            }
            is SignalingMessage.Consume -> buildJsonObject {
                put("producerId", message.producerId)
            }
            is SignalingMessage.ResumeConsumer -> buildJsonObject {
                put("consumerId", message.consumerId)
            }
            is SignalingMessage.RestartIce -> buildJsonObject {
                put("transportId", message.transportId)
            }
            else -> {
                Logger.w("WS [Warning] Unsupported message type for sending: ${message::class.simpleName}")
                return
            }
        }

        val envelope = buildJsonObject {
            put("method", method)
            put("data", messageJson)
        }

        if (_connectionState.value == ConnectionState.CONNECTED) {
            Logger.d("WS [SEND] $method: ${envelope.toString().take(200)}")
            webSocket?.send(envelope.toString())
        } else {
            Logger.d("WS [QUEUE] $method")
            messageQueue.add(method to messageJson)
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Logger.i("WS [Connected]")
        _connectionState.value = ConnectionState.CONNECTED
        reconnectionAttempts = 0

        // Send queued messages
        while (messageQueue.isNotEmpty()) {
            messageQueue.poll()?.let { (method, data) ->
                val envelope = buildJsonObject {
                    put("method", method)
                    put("data", data)
                }
                Logger.d("WS [SEND QUEUED] $method")
                webSocket.send(envelope.toString())
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Logger.d("WS [RECV] ${text.take(300)}")

        scope.launch {
            try {
                val envelope = json.parseToJsonElement(text).jsonObject
                val method = envelope["method"]?.jsonPrimitive?.content
                val data = envelope["data"]?.jsonObject

                if (method == null) {
                    Logger.w("WS [Warning] No method field in message")
                    return@launch
                }

                val message = parseMediasoupMessage(method, data)
                if (message != null) {
                    _messages.emit(message)
                } else {
                    Logger.w("WS [Warning] Failed to parse message for method: $method")
                }

            } catch (e: Exception) {
                Logger.e("WS [ERROR] Parse failed: ${e.message}", e)
                _messages.emit(SignalingMessage.Error("Parse error: ${e.message}"))
            }
        }
    }

    private fun parseMediasoupMessage(method: String, data: JsonObject?): SignalingMessage? {
        return try {
            when (method) {
                "joined" -> {
                    val peers = data?.get("peers")?.jsonArray?.map {
                        it.jsonPrimitive.content
                    } ?: emptyList()
                    SignalingMessage.Joined(peers)
                }

                "routerRtpCapabilities" -> {
                    val capabilities = data?.get("capabilities") ?: JsonObject(emptyMap())
                    SignalingMessage.RouterRtpCapabilities(capabilities)
                }

                "transportCreated" -> {
                    val transportId = data?.get("transportId")?.jsonPrimitive?.content
                        ?: data?.get("id")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing transportId")

                    val iceParameters = data?.get("iceParameters") ?: JsonObject(emptyMap())
                    val iceCandidates = data?.get("iceCandidates") ?: JsonArray(emptyList())
                    val dtlsParameters = data?.get("dtlsParameters") ?: JsonObject(emptyMap())

                    SignalingMessage.TransportCreated(
                        id = transportId,
                        iceParameters = iceParameters,
                        iceCandidates = iceCandidates,
                        dtlsParameters = dtlsParameters
                    )
                }

                "transportConnected" -> {
                    val transportId = data?.get("transportId")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing transportId")
                    SignalingMessage.TransportConnected(transportId)
                }

                "produced" -> {
                    val id = data?.get("id")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing producer id")
                    val answer = data["answer"]?.jsonPrimitive?.content
                    SignalingMessage.Produced(id, answer)
                }

                "consumed" -> {
                    val id = data?.get("id")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing consumer id")
                    val producerId = data["producerId"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing producerId")
                    val kind = data["kind"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing kind")
                    val rtpParameters = data["rtpParameters"] ?: JsonObject(emptyMap())

                    SignalingMessage.Consumed(id, producerId, kind, rtpParameters)
                }

                "newProducer" -> {
                    val producerId = data?.get("producerId")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing producerId")
                    val peerId = data["peerId"]?.jsonPrimitive?.content ?: "unknown"
                    val kind = data["kind"]?.jsonPrimitive?.content ?: "audio"
                    SignalingMessage.NewProducer(producerId, peerId, kind)
                }

                "producerClosed" -> {
                    val producerId = data?.get("producerId")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing producerId")
                    SignalingMessage.ProducerClosed(producerId)
                }

                "peerLeft" -> {
                    val peerId = data?.get("peerId")?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing peerId")
                    SignalingMessage.PeerLeft(peerId)
                }

                "error" -> {
                    val errorMsg = data?.get("error")?.jsonPrimitive?.content
                        ?: data?.get("message")?.jsonPrimitive?.content
                        ?: "Unknown error"
                    SignalingMessage.Error(errorMsg)
                }

                else -> {
                    Logger.w("WS [Warning] Unknown method: $method")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.e("WS [ERROR] Failed to parse $method: ${e.message}", e)
            SignalingMessage.Error("Parse error for $method: ${e.message}")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Logger.i("WS [Closing] code=$code, reason=$reason")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Logger.i("WS [Closed] code=$code")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Logger.e("WS [Failure] ${t.message}", t)
        _connectionState.value = ConnectionState.ERROR
        handleReconnection()
    }

    private fun handleReconnection() {
        if (reconnectionAttempts >= 5) {
            Logger.w("WS [Reconnection] Max attempts reached (5)")
            return
        }

        scope.launch(Dispatchers.IO) {
            val delayMillis = (1000 * 2.0.pow(reconnectionAttempts.toDouble())).toLong()
            reconnectionAttempts++
            Logger.i("WS [Reconnection] Attempt #$reconnectionAttempts after ${delayMillis}ms")
            delay(delayMillis)
            connect()
        }
    }
}
