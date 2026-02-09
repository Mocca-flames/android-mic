# Server Requirements for 2-Way Audio and Mix Minus

## Overview

This document outlines the server-side requirements for supporting 2-way audio and Mix Minus functionality with the Android FM Digital Broadcaster client. The Android client is fully implemented and ready to connect to a Mediasoup server that supports the requirements outlined below.

## Architecture Overview

The Android client uses a **peer-to-peer routing model** where each listener receives the original stream directly from the broadcaster. The server acts as a distribution point (router) rather than a mixer.

### Key Characteristics

- **Single Producer per Broadcaster**: Each peer creates exactly one producer
- **Individual Consumers per Listener**: Each listener creates their own consumer
- **No Server-Side Mixing**: The server does not combine multiple streams
- **Independent Streams**: Each listener receives a separate stream copy
- **Mix Minus via Client Control**: The client handles Mix Minus through playback control

## Server-Side Requirements

### 1. Producer Creation and Notification

**Requirement:** When a peer creates a producer, notify all other peers about the new producer.

**Message Flow:**

```
Client → Server: {
  "method": "produce",
  "data": {
    "transportId": "...",
    "kind": "audio",
    "rtpParameters": { ... }
  }
}

Server → Client (producer): {
  "method": "produced",
  "data": {
    "id": "producer.id"
  }
}

Server → All Other Peers: {
  "method": "newProducer",
  "data": {
    "producerId": "producer.id",
    "peerId": "peer.id",
    "kind": "audio"
  }
}
```

**Implementation:**

```javascript
// Handle produce request
case 'produce':
  const transport = peer.sendTransport;
  const producer = await transport.produce({
    kind: data.kind,
    rtpParameters: data.rtpParameters
  });

  // Store producer
  peer.producers.set(producer.id, producer);

  // Send producer ID back to creating peer
  socket.send(JSON.stringify({
    method: 'produced',
    data: { id: producer.id }
  }));

  // Notify all other peers about new producer
  const newProducerNotification = {
    method: 'newProducer',
    data: {
      producerId: producer.id,
      peerId: peer.id,
      kind: data.kind
    }
  };

  peers.forEach(otherPeer => {
    if (otherPeer.id !== peer.id) {
      otherPeer.socket.send(JSON.stringify(newProducerNotification));
    }
  });
  break;
```

**Behavior:**

- Producer is created and stored in peer's producers map
- Producer ID is returned to the creating peer
- All other peers are notified about the new producer
- Notification includes producer ID, peer ID, and kind

### 2. Consumer Creation

**Requirement:** When a peer requests to consume a producer, create a consumer and return consumer details.

**Message Flow:**

```
Client → Server: {
  "method": "consume",
  "data": {
    "producerId": "..."
  }
}

Server → Client: {
  "method": "consumed",
  "data": {
    "id": "consumer.id",
    "producerId": "...",
    "kind": "audio",
    "rtpParameters": { ... }
  }
}
```

**Implementation:**

```javascript
// Handle consume request
case 'consume':
  const producer = getProducerById(data.producerId);
  if (!producer) {
    socket.send(JSON.stringify({
      method: 'error',
      data: { error: 'Producer not found' }
    }));
    return;
  }

  const consumer = await peer.recvTransport.consume({
    producerId: data.producerId,
    rtpCapabilities: peer.rtpCapabilities
  });

  // Store consumer
  peer.consumers.set(consumer.id, consumer);

  // Send consumer details back to client
  socket.send(JSON.stringify({
    method: 'consumed',
    data: {
      id: consumer.id,
      producerId: data.producerId,
      kind: consumer.kind,
      rtpParameters: consumer.rtpParameters
    }
  }));
  break;
```

**Behavior:**

- Consumer is created from the specified producer
- Consumer is stored in peer's consumers map
- Consumer ID, producer ID, kind, and RTP parameters are returned to client
- Consumer is initially paused (not streaming)

### 3. Consumer Resume

**Requirement:** When a peer requests to resume a consumer, start streaming audio to that consumer.

**Message Flow:**

```
Client → Server: {
  "method": "resumeConsumer",
  "data": {
    "consumerId": "..."
  }
}
```

**Implementation:**

```javascript
// Handle resumeConsumer request
case 'resumeConsumer':
  const consumer = peer.consumers.get(data.consumerId);
  if (consumer) {
    await consumer.resume();
    logger.info(`Consumer ${data.consumerId} resumed`);
  }
  break;
```

**Behavior:**

- Server resumes the specified consumer
- Audio streaming begins immediately
- No response required (fire-and-forget)

### 4. Producer Cleanup

**Requirement:** When a peer disconnects, close all producers and notify all consumers.

**Implementation:**

```javascript
// When peer disconnects
socket.on("disconnect", () => {
  // Close all producers
  peer.producers.forEach((producer) => {
    producer.close();

    // Notify all consumers
    const producerClosedNotification = {
      method: "producerClosed",
      data: { producerId: producer.id },
    };

    peers.forEach((otherPeer) => {
      otherPeer.socket.send(JSON.stringify(producerClosedNotification));
    });
  });

  peer.producers.clear();

  // Close all consumers
  peer.consumers.forEach((consumer) => consumer.close());
  peer.consumers.clear();

  // Remove peer from room
  peers.delete(peer.id);
});
```

**Behavior:**

- All producers are closed
- All consumers are notified via `producerClosed` message
- All consumers are closed
- Peer is removed from room

### 5. Transport Management

**Requirement:** Support separate send and receive transports per peer.

**Implementation:**

```javascript
// Store both transports per peer
peer.sendTransport = sendTransport;
peer.recvTransport = recvTransport;

// Handle transport creation
case 'createTransport':
  const transport = await router.createWebRtcTransport({
    enableUdp: true,
    enableTcp: true,
    preferUdp: true,
    enableSctp: true
  });

  if (data.direction === 'send') {
    peer.sendTransport = transport;
  } else if (data.direction === 'recv') {
    peer.recvTransport = transport;
  }

  socket.send(JSON.stringify({
    method: 'transportCreated',
    data: {
      id: transport.id,
      iceParameters: transport.iceParameters,
      iceCandidates: transport.iceCandidates,
      dtlsParameters: transport.dtlsParameters
    }
  }));
  break;
```

**Behavior:**

- Each peer can have separate send and receive transports
- Transport ID is returned to client
- ICE and DTLS parameters are provided

### 6. ICE Restart Support

**Requirement:** Support ICE restart for connection recovery.

**Implementation:**

```javascript
case 'restartIce':
  const transport = getTransportById(data.transportId);
  if (transport) {
    const iceParameters = await transport.restartIce();

    socket.send(JSON.stringify({
      method: 'transportCreated',
      data: {
        id: transport.id,
        iceParameters: iceParameters,
        iceCandidates: transport.iceCandidates,
        dtlsParameters: transport.dtlsParameters
      }
    }));
  }
  break;
```

**Behavior:**

- Server restarts ICE for the specified transport
- New ICE parameters are returned to client
- Connection is re-established without full transport recreation

## Message Protocol Summary

### Client → Server Messages

| Method                     | Purpose                     | Required Parameters                    |
| -------------------------- | --------------------------- | -------------------------------------- |
| `getRouterRtpCapabilities` | Get server RTP capabilities | None                                   |
| `createTransport`          | Create WebRTC transport     | `direction` ("send" or "recv")         |
| `connectTransport`         | Connect transport with DTLS | `transportId`, `dtlsParameters`        |
| `produce`                  | Create producer             | `transportId`, `kind`, `rtpParameters` |
| `consume`                  | Create consumer             | `producerId`                           |
| `resumeConsumer`           | Resume consumer             | `consumerId`                           |
| `restartIce`               | Restart ICE for transport   | `transportId`                          |

### Server → Client Messages

| Method                  | Purpose                 | Response Data                                            |
| ----------------------- | ----------------------- | -------------------------------------------------------- |
| `routerRtpCapabilities` | Server RTP capabilities | `capabilities`                                           |
| `transportCreated`      | Transport created       | `id`, `iceParameters`, `iceCandidates`, `dtlsParameters` |
| `transportConnected`    | Transport connected     | `transportId`                                            |
| `produced`              | Producer created        | `id`                                                     |
| `consumed`              | Consumer created        | `id`, `producerId`, `kind`, `rtpParameters`              |
| `newProducer`           | New producer available  | `producerId`, `peerId`, `kind`                           |
| `producerClosed`        | Producer closed         | `producerId`                                             |
| `error`                 | Error occurred          | `error`                                                  |

## Complete 2-Way Audio Flow

### Scenario: Broadcaster and Studio

```
1. Broadcaster connects
   Broadcaster → Server: createTransport (send)
   Server → Broadcaster: transportCreated
   Broadcaster → Server: connectTransport
   Server → Broadcaster: transportConnected
   Broadcaster → Server: produce
   Server → Broadcaster: produced
   Server → Studio: newProducer (broadcaster's producer)

2. Studio connects
   Studio → Server: createTransport (send)
   Server → Studio: transportCreated
   Studio → Server: connectTransport
   Server → Studio: transportConnected
   Studio → Server: produce
   Server → Studio: produced
   Server → Broadcaster: newProducer (studio's producer)

3. Studio consumes broadcaster's audio
   Studio → Server: createTransport (recv)
   Server → Studio: transportCreated
   Studio → Server: connectTransport
   Server → Studio: transportConnected
   Studio → Server: consume (broadcaster's producerId)
   Server → Studio: consumed
   Studio → Server: resumeConsumer
   [Studio receives broadcaster's audio]

4. Broadcaster consumes studio's audio
   Broadcaster → Server: createTransport (recv)
   Server → Broadcaster: transportCreated
   Broadcaster → Server: connectTransport
   Server → Broadcaster: transportConnected
   Broadcaster → Server: consume (studio's producerId)
   Server → Broadcaster: consumed
   Broadcaster → Server: resumeConsumer
   [Broadcaster receives studio's audio]
   [Broadcaster can toggle Mix Minus via monitor switch]
```

## Audio Quality Requirements

### Codec Configuration

The Android client sends audio with the following Opus configuration:

```json
{
  "codecs": [
    {
      "mimeType": "audio/opus",
      "payloadType": 111,
      "clockRate": 48000,
      "channels": 2,
      "parameters": {
        "useinbandfec": "1",
        "usedtx": "0",
        "stereo": "1",
        "sprop-stereo": "1",
        "maxaveragebitrate": "128000"
      }
    }
  ]
}
```

**Requirements:**

- Support Opus codec at 48kHz
- Support stereo audio (2 channels)
- Support in-band FEC
- Support max bitrate of 128kbps

### Network Quality Monitoring

The Android client monitors the following metrics:

- **Round Trip Time (RTT)**: Should be < 200ms
- **Packet Loss**: Should be < 1%
- **Jitter**: Should be < 50ms

**Server Requirements:**

- Provide accurate RTT statistics via WebRTC stats
- Minimize packet loss through proper routing
- Maintain low jitter through proper buffering

## Testing Checklist

### Server-Side Testing

- [ ] Producer creation and storage
- [ ] Producer notification to all peers
- [ ] Consumer creation from producer ID
- [ ] Consumer resume functionality
- [ ] Producer cleanup on disconnect
- [ ] Consumer cleanup on disconnect
- [ ] Transport creation (send and recv)
- [ ] Transport connection with DTLS
- [ ] ICE restart functionality
- [ ] Error handling for invalid requests

### Integration Testing

- [ ] Broadcaster can create producer
- [ ] Studio receives newProducer notification
- [ ] Studio can create consumer
- [ ] Studio receives broadcaster's audio
- [ ] Studio can create producer
- [ ] Broadcaster receives newProducer notification
- [ ] Broadcaster can create consumer
- [ ] Broadcaster receives studio's audio
- [ ] Mix Minus works (broadcaster can toggle playback)
- [ ] Connection recovery works after network interruption

## Notes for Server Team

1. **No Server-Side Mixing Required**: The client handles Mix Minus through playback control. The server only needs to route individual streams.

2. **Individual Stream Routing**: Each listener receives their own consumer. No need to combine streams on the server.

3. **Separate Transports**: Each peer has separate send and receive transports. This is intentional for Mix Minus functionality.

4. **Producer-Consumer Model**: Follow standard Mediasoup producer/consumer pattern. One producer per peer, multiple consumers per producer.

5. **Notification System**: Critical for 2-way audio. Peers must be notified when new producers are available.

6. **Connection Recovery**: Support ICE restart for robust connection handling.

7. **Audio Quality**: Support Opus codec with stereo and in-band FEC for professional broadcast quality.

## Contact

For questions or clarifications about the Android client implementation, refer to:

- [`MediasoupManager.kt`](java/com/fm/digital/webrtc/MediasoupManager.kt) - Core Mediasoup logic
- [`SignalingMessage.kt`](java/com/fm/digital/networking/SignalingMessage.kt) - Message protocol definitions
- [`WebRtcEngine.kt`](java/com/fm/digital/webrtc/WebRtcEngine.kt) - WebRTC audio configuration
