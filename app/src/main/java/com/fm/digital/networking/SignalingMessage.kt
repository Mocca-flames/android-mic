package com.fm.digital.networking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mediasoup signaling messages
 *
 * Protocol format:
 * Client → Server: { "method": "methodName", "data": { ... } }
 * Server → Client: { "method": "methodName", "data": { ... } }
 */
sealed class SignalingMessage {

    // ==================== CLIENT → SERVER ====================

    /**
     * Join a room
     * Request: { "method": "join", "data": { "roomId": "...", "peerId": "..." } }
     */
    data class Join(
        val roomId: String,
        val peerId: String
    ) : SignalingMessage()

    /**
     * Get router RTP capabilities
     * Request: { "method": "getRouterRtpCapabilities", "data": {} }
     */
    object GetRouterRtpCapabilities : SignalingMessage()

    /**
     * Create WebRTC transport (send or recv)
     * Request: { "method": "createTransport", "data": { "direction": "send" } }
     */
    data class CreateTransport(
        val direction: String // "send" or "recv"
    ) : SignalingMessage()

    /**
     * Connect transport with DTLS parameters
     * Request: { "method": "connectTransport", "data": { "transportId": "...", "dtlsParameters": {...} } }
     */
    data class ConnectTransport(
        val transportId: String,
        val dtlsParameters: JsonElement
    ) : SignalingMessage()

    /**
     * Produce media (audio/video)
     * Request: { "method": "produce", "data": { "transportId": "...", "kind": "audio", "rtpParameters": {...} } }
     */
    data class Produce(
        val transportId: String,
        val kind: String, // "audio" or "video"
        val rtpParameters: JsonElement
    ) : SignalingMessage()

    /**
     * Consume media from another producer
     * Request: { "method": "consume", "data": { "producerId": "..." } }
     */
    data class Consume(
        val producerId: String
    ) : SignalingMessage()

    /**
     * Resume a paused consumer
     * Request: { "method": "resumeConsumer", "data": { "consumerId": "..." } }
     */
    data class ResumeConsumer(
        val consumerId: String
    ) : SignalingMessage()

    /**
     * Restart ICE for a transport
     * Request: { "method": "restartIce", "data": { "transportId": "..." } }
     */
    data class RestartIce(
        val transportId: String
    ) : SignalingMessage()


    // ==================== SERVER → CLIENT ====================

    /**
     * Joined room successfully
     * Response: { "method": "joined", "data": { "peers": ["peer1", "peer2"] } }
     */
    data class Joined(
        val peers: List<String> = emptyList()
    ) : SignalingMessage()

    /**
     * Router RTP capabilities
     * Response: { "method": "routerRtpCapabilities", "data": { "capabilities": {...} } }
     */
    data class RouterRtpCapabilities(
        val capabilities: JsonElement
    ) : SignalingMessage()

    /**
     * Transport created successfully
     * Response: { "method": "transportCreated", "data": {
     *   "transportId": "...",
     *   "iceParameters": {...},
     *   "iceCandidates": [...],
     *   "dtlsParameters": {...}
     * } }
     */
    data class TransportCreated(
        val id: String, // transportId
        val iceParameters: JsonElement,
        val iceCandidates: JsonElement,
        val dtlsParameters: JsonElement
    ) : SignalingMessage()

    /**
     * Transport connected successfully
     * Response: { "method": "transportConnected", "data": { "transportId": "..." } }
     */
    data class TransportConnected(
        val transportId: String
    ) : SignalingMessage()

    /**
     * Producer created successfully
     * Response: { "method": "produced", "data": { "id": "...", "answer": "..." } }
     *
     * Note: "answer" is optional SDP answer from server (some implementations include it)
     */
    data class Produced(
        val id: String, // producerId
        val answer: String? = null
    ) : SignalingMessage()

    /**
     * Consumer created successfully
     * Response: { "method": "consumed", "data": {
     *   "id": "...",
     *   "producerId": "...",
     *   "kind": "audio",
     *   "rtpParameters": {...}
     * } }
     */
    data class Consumed(
        val id: String, // consumerId
        val producerId: String,
        val kind: String,
        val rtpParameters: JsonElement
    ) : SignalingMessage()

    /**
     * New producer available (peer joined)
     * Notification: { "method": "newProducer", "data": {
     *   "producerId": "...",
     *   "peerId": "...",
     *   "kind": "audio"
     * } }
     */
    data class NewProducer(
        val producerId: String,
        val peerId: String,
        val kind: String
    ) : SignalingMessage()

    /**
     * Producer closed (peer left or stopped producing)
     * Notification: { "method": "producerClosed", "data": { "producerId": "..." } }
     */
    data class ProducerClosed(
        val producerId: String
    ) : SignalingMessage()

    /**
     * Peer left the room
     * Notification: { "method": "peerLeft", "data": { "peerId": "..." } }
     */
    data class PeerLeft(
        val peerId: String
    ) : SignalingMessage()

    /**
     * Error from server
     * Response: { "method": "error", "data": { "error": "error message" } }
     */
    data class Error(
        val error: String
    ) : SignalingMessage()
}