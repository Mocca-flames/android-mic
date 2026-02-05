# Broadcaster (Android DJ/Producer) Message Protocol



This document describes the WebSocket message protocol for broadcaster clients (Android DJs/producers) connecting to the WebRTC signaling server.



## Table of Contents

1. [Message Format Requirements](#message-format-requirements)

2. [Complete Broadcaster Flow](#complete-broadcaster-flow)

3. [Critical Differences: Broadcaster vs Receiver](#critical-differences-broadcaster-vs-receiver)

4. [Message Reference](#message-reference)

5. [Common Production Failures & Debugging](#common-production-failures--debugging)

6. [Error Handling](#error-handling)



---



## Message Format Requirements



### Use `action` Field, NOT `type` Field



All messages to the server **MUST** include an `action` field at the root level. The server does **NOT** recognize a `type` field.



**❌ Incorrect:**

```json

{

  "type": "produce",

  "kind": "audio"

}

```



**✅ Correct:**

```json

{

  "action": "produce",

  "transportId": "transport-uuid",

  "kind": "audio",

  "rtpParameters": { ... }

}

```



### Field Location



All fields must be at the **root level** of the JSON message. Do not nest parameters under a `data` or `payload` field.



**❌ Incorrect:**

```json

{

  "action": "produce",

  "data": {

    "transportId": "transport-uuid",

    "kind": "audio"

  }

}

```



**✅ Correct:**

```json

{

  "action": "produce",

  "transportId": "transport-uuid",

  "kind": "audio",

  "rtpParameters": { ... }

}

```



---



## Complete Broadcaster Flow



### 1. Connect WebSocket



Establish a WebSocket connection to the server:

```

ws://<server>:<port>/signaling

```



### 2. Join Room



Send the `join` action with `roomId` and `peerId` at the root level. The peer becomes a broadcaster when it successfully produces audio.



**Request:**

```json

{

  "action": "join",

  "roomId": "studio",

  "peerId": "dj-android-001"

}

```



**Server Response (success):**

```json

{

  "action": "joined",

  "data": {

    "peerId": "dj-android-001",

    "roomId": "studio"

  }

}

```



### 3. Get Router RTP Capabilities



Request the router's RTP capabilities to configure your sending peer connection.



**Request:**

```json

{

  "action": "getRouterRtpCapabilities"

}

```



**Server Response:**

```json

{

  "action": "routerRtpCapabilities",

  "data": {

    "rtpCapabilities": {

      "codecs": [...],

      "headerExtensions": [...],

      "fecMechanisms": [...]

    }

  }

}

```



### 4. Create Send Transport



Create a WebRTC transport for **sending** media. **CRITICAL:** Use `direction: "send"` (NOT `"recv"`).



**Request:**

```json

{

  "action": "createTransport",

  "direction": "send"

}

```



**Server Response:**

```json

{

  "action": "transportCreated",

  "data": {

    "transportId": "transport-uuid",

    "iceParameters": "{\"usernameFragment\":\"...\",\"password\":\"...\",\"iceLite\":true}",

    "iceCandidates": "[{\"foundation\":\"...\",\"priority\":...}]",

    "dtlsParameters": "{\"role\":\"auto\",\"fingerprints\":[{\"algorithm\":\"sha-256\",\"value\":\"...\"}]}"

  }

}

```



**Note:** The server returns `iceParameters`, `iceCandidates`, and `dtlsParameters` as **JSON strings**. Parse them before use.



### 5. Connect Transport with DTLS Parameters



Connect the transport using the DTLS parameters from your Android PeerConnection.



**Request:**

```json

{

  "action": "connectTransport",

  "transportId": "transport-uuid",

  "dtlsParameters": {

    "role": "auto",

    "fingerprints": [

      {

        "algorithm": "sha-256",

        "value": "AB:CD:EF:..."

      }

    ]

  }

}

```



**Server Response (success):**

```json

{

  "action": "transportConnected",

  "data": {

    "transportId": "transport-uuid"

  }

}

```



### 6. Produce Audio



Send the `produce` action with `rtpParameters` obtained from Android's `RTCRtpSender.getParameters()`. **This is the critical step that makes the peer a broadcaster.**



**Request:**

```json

{

  "action": "produce",

  "transportId": "transport-uuid",

  "kind": "audio",

  "rtpParameters": {

    "codecs": [

      {

        "mimeType": "audio/opus",

        "payloadType": 111,

        "clockRate": 48000,

        "channels": 2,

        "parameters": {

          "minptime": 10,

          "useinbandfec": 1

        }

      }

    ],

    "headerExtensions": [

      {

        "uri": "urn:ietf:params:rtp-hdrext:sdes:mid",

        "id": 1

      }

    ],

    "encodings": [

      {

        "ssrc": 123456789

      }

    ],

    "rtcp": {

      "cname": "...",

      "reducedSize": true

    }

  }

}

```



**Server Response (success):**

```json

{

  "action": "produced",

  "data": {

    "id": "producer-uuid"

  }

}

```



**Upon successful produce:**

- Server calls `setBroadcaster(roomId, peerId)` - the peer is now marked as the broadcaster

- Server calls `notifyListeners(roomId, producerId)` - all listeners receive `newProducer` notification



### 7. Server Notifies Listeners



After successful production, the server automatically notifies all non-broadcaster peers in the room.



**Server sends to all listeners:**

```json

{

  "action": "newProducer",

  "data": {

    "producerId": "producer-uuid"

  }

}

```



---



## Critical Differences: Broadcaster vs Receiver



| Aspect | Broadcaster (DJ) | Receiver (Listener) |

|--------|------------------|---------------------|

| **Transport Direction** | `"send"` | `"recv"` |

| **Must Call `produce`** | ✅ **Required** - This makes the peer a broadcaster | ❌ Never calls `produce` |

| **Receives `newProducer`** | ❌ Not sent to broadcaster | ✅ Received when broadcaster produces |

| **Calls `consume`** | ❌ Never consumes | ✅ Calls `consume` after `newProducer` |

| **Set as Broadcaster** | ✅ Server calls `setBroadcaster()` | ❌ Remains listener |

| **RTP Parameters Source** | From `RTCRtpSender.getParameters()` | From server's `consumed` response |



### Key Implementation Notes



1. **Direction is "send" for broadcaster:**

   ```json

   {

     "action": "createTransport",

     "direction": "send"  // <-- CRITICAL: Must be "send"

   }

   ```



2. **Must call "produce" action:**

    - Listeners never call `produce`

    - Only the broadcaster calls `produce` to send audio

    - This action triggers `setBroadcaster()` and `notifyListeners()`



3. **rtpParameters must be an object from RTCRtpSender:**

   ```kotlin

   // Android Kotlin example

   val sender = peerConnection.addTrack(audioTrack)

   val rtpParameters = sender.parameters  // <-- Must pass this object

   

   // Send rtpParameters to server (as JSON object, NOT string)

   ```



---



## Message Reference



### Client → Server Messages (Broadcaster)



| Action | Required Fields | Optional Fields | Description |

|--------|----------------|-----------------|-------------|

| `join` | `roomId`, `peerId` | - | Join a room (becomes broadcaster on produce) |

| `getRouterRtpCapabilities` | - | - | Get router's RTP capabilities |

| `createTransport` | `direction` | - | Create WebRTC transport. **Must use `direction: "send"`** |

| `connectTransport` | `transportId`, `dtlsParameters` | - | Connect transport with DTLS from PeerConnection |

| `produce` | `transportId`, `kind`, `rtpParameters` | - | Start producing audio. **Critical:** `rtpParameters` must be object from `RTCRtpSender.getParameters()` |



### Server → Client Messages



| Action | Description |

|--------|-------------|

| `joined` | Successful room join confirmation |

| `routerRtpCapabilities` | Router RTP capabilities response |

| `transportCreated` | Transport created with ICE/DTLS params (JSON strings) |

| `transportConnected` | Transport connected confirmation |

| `produced` | Producer created successfully, broadcaster set, listeners notified |

| `error` | Error response with details |



---



## Common Production Failures & Debugging



### 1. DTLS Not Connecting



**Symptoms:**

- `connectTransport` returns error

- Transport connection fails

- No audio flowing



**Debugging:**

```json

// Server error response:

{

  "action": "error",

  "error": "Missing dtlsParameters.fingerprints array. Provide the DTLS fingerprints from your RTCPeerConnection.",

  "details": "Ensure you are sending the complete dtlsParameters from your RTCPeerConnection including the fingerprints array"

}

```



**Solutions:**

- Ensure you're extracting DTLS parameters from the Android `PeerConnection` after ICE gathering completes

- `dtlsParameters.fingerprints` must be an array with at least one fingerprint object

- Format: `{ "algorithm": "sha-256", "value": "..." }`



### 2. rtpParameters Format Issues



**Symptoms:**

- `produce` action fails

- Error: "rtpParameters must be an object"



**Debugging:**

```json

// Server error response:

{

  "action": "error",

  "error": "rtpParameters must be an object, got string. You must provide actual RTCRtpParameters from your peer connection, not a placeholder string.",

  "details": "Ensure you are sending complete rtpParameters from your RTCPeerConnection RTCRtpSender.getParameters()"

}

```



**Critical Requirements:**

- `rtpParameters` **MUST** be an object, NOT a string

- Must contain `codecs` array: `rtpParameters.codecs`

- Must be actual parameters from `RTCRtpSender.getParameters()`, not placeholder data



**✅ Correct (Kotlin/Android):**

```kotlin

val sender = peerConnection.addTrack(audioTrack)

val params = sender.parameters  // This is an object



// Send as JSON object:

val message = JSONObject().apply {

    put("action", "produce")

    put("transportId", transportId)

    put("kind", "audio")

    put("rtpParameters", JSONObject(params.toString()))  // Convert to JSON object

}

```



**❌ Incorrect:**

```kotlin

// Sending as string instead of object:

put("rtpParameters", params.toString())  // WRONG!

```



### 3. Produce Action Failing



**Common Errors:**



**Missing transportId:**

```json

{

  "action": "error",

  "error": "Missing transportId field"

}

```



**Invalid kind:**

```json

{

  "action": "error",

  "error": "Missing or invalid kind field. Expected: \"audio\" or \"video\""

}

```



**Missing codecs:**

```json

{

  "action": "error",

  "error": "Missing rtpParameters.codecs array. Provide the codecs from your RTCRtpSender.getParameters()"

}

```



**No supported codecs after filtering:**

```json

{

  "action": "error",

  "error": "No supported codecs remaining after filtering. Client sent: [audio/red, audio/ulpfec]. Server supports: [audio/opus]. Ensure Android client is configured to use Opus codec (audio/opus). See broadcaster_plan.md for Android codec configuration."

}

```



**Empty codecs array:**

If the server logs show `Original client codecs: none` and the `codecs` array in `rtpParameters` is `[]`, it means the Android client is calling `sender.getParameters()` too early or the transceiver is not correctly initialized.



**Root Cause:**

The Android client is not including the Opus codec (`audio/opus`) in its `RTCRtpSender.getParameters().codecs` array. FEC codecs (RED, ULPFEC) are accepted by the server but a media codec (Opus) is **REQUIRED**.



**Android Client Fix:**



The Android PeerConnection MUST be configured to use Opus BEFORE calling `produce`. **CRITICAL:** You must call `setLocalDescription` before `sender.getParameters()` will return the negotiated codecs.



```kotlin

// Step 1: Create PeerConnection with codec preferences

// ... (see below for full example)



// Step 2: Add track and set codec preferences

val transceiver = peerConnection.addTransceiver(audioTrack, transceiverInit)

val capabilities = PeerConnectionFactory.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)

val opusCodec = capabilities.codecs.find { it.name.equals("opus", true) }

if (opusCodec != null) {

    transceiver.setCodecPreferences(listOf(opusCodec))

}



// Step 3: Create Offer and Set Local Description

peerConnection.createOffer(object : SdpObserver {

    override fun onCreateSuccess(desc: SessionDescription) {

        peerConnection.setLocalDescription(object : SdpObserver {

            override fun onSetSuccess() {

                // ONLY NOW will sender.getParameters().codecs be populated!

                val sender = peerConnection.senders.find { it.track()?.id() == audioTrack.id() }

                val params = sender?.parameters

                

                if (params != null && params.codecs.isNotEmpty()) {

                    // Send to server

                    signalingClient.produce(transportId, "audio", params)

                } else {

                    Log.e("WebRTC", "Codecs still empty! Ensure setLocalDescription finished.")

                }

            }

        }, desc)

    }

}, MediaConstraints())

```



**Server-Side Filtering:**

The server accepts FEC mechanisms (RED/ULPFEC) and filters only truly unsupported codecs:



```javascript

// From signalingServer.js

const fecMimeTypes = ['audio/red', 'audio/ulpfec', 'video/ulpfec', 'video/flexfec-03', 'video/flexfec'];



const filteredCodecs = rtpParameters.codecs.filter(codec => {

  const mimeTypeLower = codec.mimeType.toLowerCase();

  

  // Always accept FEC mechanisms

  const isFec = fecMimeTypes.some(fec => mimeTypeLower.includes(fec));

  if (isFec) return true;

  

  // Check if codec is in router's supported list

  return supportedCodecs.some(supported => {

    return supported.mimeType.toLowerCase() === mimeTypeLower &&

           supported.clockRate === codec.clockRate &&

           (supported.channels === undefined || supported.channels === codec.channels);

  });

});

```



**Debugging Tips:**

1. Check server logs for "📋 Client sent codecs:" to see what the Android client actually sent

2. Ensure `audio/opus` appears in the client's codec list

3. If only `audio/red` and `audio/ulpfec` appear, the Opus codec configuration is missing

4. Server logs will show "✅ Accepting FEC codec:" for RED/ULPFEC



### 4. No newProducer Notification Being Sent to Listeners



**Symptoms:**

- `produce` succeeds (server responds with `produced`)

- Listeners never receive `newProducer` notification

- Listeners cannot hear the audio



**Debugging Steps:**



1. **Check if `setBroadcaster` was called:**

    - Server calls `setBroadcaster(ws.roomId, ws.peerId)` immediately after successful `transport.produce()`

    - Check server logs for: `🎙️ Broadcaster set: <peerId> in room <roomId>`



2. **Check if listeners are in the room:**

    - Server logs: `📢 Notified <N> listeners about producer <producerId>`

    - If N=0, no listeners are connected



3. **Verify listener filtering:**

    - `notifyListeners` excludes the broadcaster: `p.id !== room.broadcasterPeerId`

    - If the broadcaster is somehow in the listeners list, they won't be notified (but this shouldn't happen)



**Flow Verification:**

```

Broadcaster          Server                  Listeners

   |                    |                        |

   |---- produce -----> |                        |

   |                    |-- setBroadcaster()     |

   |                    |-- notifyListeners()     |

   |<--- produced ----- |                        |

   |                    |---- newProducer ------> |

   |                    |---- newProducer ------> |

```



### 5. Codecs Being Filtered Out



**Server-side filtering:**

The server filters codecs to only include those supported by the router:



```javascript

// From signalingServer.js

const filteredCodecs = rtpParameters.codecs.filter(codec => {

  const isSupported = supportedCodecs.some(supported => {

    return supported.mimeType.toLowerCase() === codec.mimeType.toLowerCase() &&

           supported.clockRate === codec.clockRate &&

           (supported.channels === undefined || supported.channels === codec.channels);

  });

  return isSupported;

});

```



**Header extension filtering:**

Extensions with RED in the URI are filtered out:



```javascript

const filteredHeaderExtensions = rtpParameters.headerExtensions

  ? rtpParameters.headerExtensions.filter(ext => {

      const uri = (ext.uri || '').toLowerCase();

      return !uri.includes('red');

    })

  : rtpParameters.headerExtensions;

```



**Ensure at least one codec remains after filtering, or production will fail.**



---



## Error Handling



### Error Response Format



When an error occurs, the server responds with:



```json

{

  "action": "error",

  "error": "Error message description",

  "details": "Additional details (optional)"

}

```



### Broadcaster-Specific Errors



#### Missing transportId in Produce

**Response:**

```json

{

  "action": "error",

  "error": "Missing transportId field"

}

```



#### Invalid Kind Field

**Response:**

```json

{

  "action": "error",

  "error": "Missing or invalid kind field. Expected: \"audio\" or \"video\""

}

```



#### rtpParameters Not Object

**Response:**

```json

{

  "action": "error",

  "error": "rtpParameters must be an object, got string. You must provide actual RTCRtpParameters from your peer connection, not a placeholder string.",

  "details": "Ensure you are sending complete rtpParameters from your RTCPeerConnection RTCRtpSender.getParameters()"

}

```



#### Missing rtpParameters.codecs

**Response:**

```json

{

  "action": "error",

  "error": "Missing rtpParameters.codecs array. Provide the codecs from your RTCRtpSender.getParameters()"

}

```



#### No Supported Codecs After Filtering

**Response:**

```json

{

  "action": "error",

  "error": "No supported codecs remaining after filtering. Original codecs were all unsupported (RED/ULPFEC/FLEXFEC)."

}

```



#### Transport Not Found

**Response:**

```json

{

  "action": "error",

  "error": "Transport not found"

}

```



---



## Complete Example: Broadcaster Session (Kotlin/Android-style pseudocode)



```kotlin

// 1. Connect WebSocket

val ws = WebSocketClient("ws://localhost:3000/signaling")



ws.onOpen {

    // 2. Join room

    ws.send(json {

        "action" to "join"

        "roomId" to "studio"

        "peerId" to "dj-android-001"

    })

}



ws.onMessage { message ->

    when (message.action) {

        "joined" -> {

            // 3. Get router capabilities

            ws.send(json { "action" to "getRouterRtpCapabilities" })

        }

        

        "routerRtpCapabilities" -> {

            // Store capabilities and create send transport

            device.load(message.data.rtpCapabilities)

            ws.send(json {

                "action" to "createTransport"

                "direction" to "send"  // <-- CRITICAL: "send" not "recv"

            })

        }

        

        "transportCreated" -> {

            // 4. Parse transport params

            val iceParams = JSONObject(message.data.iceParameters)

            val iceCandidates = JSONArray(message.data.iceCandidates)

            val dtlsParams = JSONObject(message.data.dtlsParameters)

            

            // Create Android PeerConnection with these params

            val peerConnection = createPeerConnection(iceParams, iceCandidates, dtlsParams)

            

            // Add audio track

            val audioTrack = createAudioTrack()

            val sender = peerConnection.addTrack(audioTrack)

            

            // Get DTLS parameters from PeerConnection

            val myDtlsParams = getDtlsParametersFromPeerConnection(peerConnection)

            

            // 5. Connect transport

            ws.send(json {

                "action" to "connectTransport"

                "transportId" to message.data.transportId

                "dtlsParameters" to myDtlsParams

            })

            

            // Store sender and transportId for produce

            this.sender = sender

            this.transportId = message.data.transportId

        }

        

        "transportConnected" -> {

            // 6. Produce audio

            val rtpParams = sender.parameters  // From RTCRtpSender

            

            ws.send(json {

                "action" to "produce"

                "transportId" to transportId

                "kind" to "audio"

                "rtpParameters" to JSONObject(rtpParams.toString())  // Must be object!

            })

        }

        

        "produced" -> {

            // Successfully producing!

            val producerId = message.data.id

            println("✅ Broadcasting audio with producer: $producerId")

            // Listeners will now receive newProducer notification

        }

        

        "error" -> {

            println("❌ Server error: ${message.error}")

            message.details?.let { println("Details: $it") }

        }

    }

}

```



---



## Supported Actions Reference



| Action | Aliases | Direction | Broadcaster Use |

|--------|---------|-----------|-----------------|

| `join` | - | Client → Server | ✅ Required |

| `getRouterRtpCapabilities` | - | Client → Server | ✅ Required |

| `createTransport` | `createWebRtcTransport` | Client → Server | ✅ Required - use `direction: "send"` |

| `connectTransport` | `connectWebRtcTransport` | Client → Server | ✅ Required |

| `produce` | - | Client → Server | ✅ **Required** - makes peer broadcaster |

| `joined` | - | Server → Client | ✅ Received |

| `routerRtpCapabilities` | - | Server → Client | ✅ Received |

| `transportCreated` | - | Server → Client | ✅ Received |

| `transportConnected` | - | Server → Client | ✅ Received |

| `produced` | - | Server → Client | ✅ Received on success |

| `error` | - | Server → Client | ✅ Received on failure |



---



## Server Implementation Reference



### setBroadcaster Function (roomManager.js)



```javascript

export function setBroadcaster(roomId, peerId) {

  const room = rooms.get(roomId);

  if (room) {

    room.broadcasterPeerId = peerId;

    console.log(`🎙️ Broadcaster set: ${peerId} in room ${roomId}`);

  }

}

```



### notifyListeners Function (signalingServer.js)



```javascript

function notifyListeners(roomId, producerId) {

  const listeners = getListenerPeers(roomId);  // Excludes broadcaster

  

  listeners.forEach(listener => {

    listener.ws.send(JSON.stringify({

      "action": "newProducer",

      "data": { producerId }

    }));

  });



  console.log(`📢 Notified ${listeners.length} listeners about producer ${producerId}`);

}

```



### handleProduce Validation (signalingServer.js)



```javascript

async function handleProduce(ws, message) {

  const { transportId, kind, rtpParameters } = message;

  

  // Validations:

  if (!transportId) throw new Error('Missing transportId field');

  if (!kind || !['audio', 'video'].includes(kind)) 

      throw new Error('Missing or invalid kind field');

  if (!rtpParameters) 

      throw new Error('Missing rtpParameters');

  if (typeof rtpParameters !== 'object') 

      throw new Error('rtpParameters must be an object');

  if (!rtpParameters.codecs) 

      throw new Error('Missing rtpParameters.codecs array');

  

  // ... codec filtering ...

  

  const producer = await transport.produce({ kind, rtpParameters });

  

  peer.producers.set(producer.id, producer);

  setBroadcaster(ws.roomId, ws.peerId);  // <-- Marks as broadcaster

  

  ws.send(JSON.stringify({

    "action": "produced",

    "data": { id: producer.id }

  }));

  

  notifyListeners(ws.roomId, producer.id);  // <-- Notifies listeners

}

```



