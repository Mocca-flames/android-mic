# Android Remote DJ Microphone MVP - Complete Blueprint

## 📋 Project Overview

**System Purpose**: Real-time remote DJ microphone broadcasting from Android device to studio Windows receiver for FM/streaming broadcast to 50,000+ listeners.

**Critical Requirements**:
- Stable 2-hour continuous operation
- Low latency (<250ms DJ to Studio)
- WebRTC only for DJ → Studio link
- Listeners served by traditional broadcast (Icecast/HLS/FM)
- Physical test devices: Android 15 & Android 14

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     PRODUCTION ARCHITECTURE                      │
└─────────────────────────────────────────────────────────────────┘

[Android DJ Phone]
     │ (WebRTC/Opus)
     │ Low Latency Link
     ▼
[mediasoup SFU Server]
     │ (Node.js)
     │ Single Producer
     │ Single Consumer
     ▼
[Windows Studio Receiver]
     │ (Browser/Native)
     │ Virtual Audio Cable
     ▼
[FM Studio Software]
     │ (OBS/Mixer/Icecast)
     ▼
[50,000+ Listeners]
   (Icecast/HLS/FM Transmitter)
   NOT WebRTC - No load on mediasoup
```

**Key Principle**: mediasoup handles ONLY the DJ uplink. Scaling is handled by traditional broadcast infrastructure.

---

## 📦 Technology Stack

### Android App
- **Language**: Kotlin
- **Build**: Gradle with Kotlin DSL
- **WebRTC**: org.webrtc:google-webrtc (latest from Maven)
- **Networking**: OkHttp for WebSocket signaling
- **Architecture**: MVVM + Clean Architecture (simple)
- **Audio**: AAudio (native, no Oboe)
- **DI**: Hilt (optional for MVP, manual DI acceptable)

### Server
- **Runtime**: Node.js 18+ LTS
- **SFU**: mediasoup 3.x
- **Signaling**: ws (WebSocket library)
- **Process Manager**: PM2 (for production)

### Studio Receiver (MVP)
- **Phase 4**: Chrome browser (fastest MVP)
- **Phase 5+**: Native Windows app (optional)
- **Audio Routing**: VB-Audio Virtual Cable / Voicemeeter

---

## 🎯 PHASE 0: Foundation & Build Environment

### Goals
- Project compiles cleanly
- No runtime crashes
- Gradle versions resolved
- Basic UI renders

### Tasks

#### 📱 Android Tasks

1. **Project Setup**
    - [ ] Create new Android Studio project (Empty Activity)
    - [ ] Configure `build.gradle.kts` (app level):
      ```kotlin
      android {
          compileSdk = 35
          defaultConfig {
              minSdk = 26
              targetSdk = 35
          }
          compileOptions {
              sourceCompatibility = JavaVersion.VERSION_17
              targetCompatibility = JavaVersion.VERSION_17
          }
      }
      ```
    - [ ] Add version catalog (`gradle/libs.versions.toml`)
    - [ ] Configure ProGuard rules (if using R8)

2. **Dependencies**
    - [ ] Add WebRTC library:
      ```kotlin
      implementation("org.webrtc:google-webrtc:1.0.+")
      ```
    - [ ] Add OkHttp:
      ```kotlin
      implementation("com.squareup.okhttp3:okhttp:4.12.+")
      ```
    - [ ] Add Kotlin coroutines:
      ```kotlin
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.+")
      ```
    - [ ] Add Lifecycle components:
      ```kotlin
      implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.+")
      implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.+")
      ```

3. **Permissions Setup**
    - [ ] Add to `AndroidManifest.xml`:
      ```xml
      <uses-permission android:name="android.permission.INTERNET" />
      <uses-permission android:name="android.permission.RECORD_AUDIO" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
      <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
      <uses-permission android:name="android.permission.WAKE_LOCK" />
      ```

4. **Project Structure**
   ```
   app/src/main/java/com/yourapp/djmic/
   ├── ui/
   │   └── MainActivity.kt
   ├── data/
   │   └── (empty for now)
   ├── domain/
   │   └── (empty for now)
   └── DjMicApplication.kt
   ```

5. **Minimal UI (MainActivity.kt)**
    - [ ] Create simple layout with:
        - TextView: Status display ("Disconnected")
        - Button: "Connect to Server" (disabled for now)
        - TextView: Version info
    - [ ] Use ConstraintLayout or LinearLayout (no Compose for MVP)
    - [ ] Add log output area (scrollable TextView)

6. **Logging Infrastructure**
    - [ ] Create `Logger.kt` utility class
    - [ ] Implement file logging to app directory
    - [ ] Add UI log display method
    - [ ] Log app lifecycle events

#### 🖥️ Server Tasks

1. **Project Setup**
    - [ ] Create server directory structure:
      ```
      server/
      ├── package.json
      ├── .env.example
      ├── src/
      │   ├── index.js
      │   ├── config.js
      │   └── logger.js
      └── README.md
      ```

2. **Package.json Configuration**
    - [ ] Initialize npm project
    - [ ] Add dependencies:
      ```json
      {
        "dependencies": {
          "mediasoup": "^3.14.0",
          "ws": "^8.16.0",
          "dotenv": "^16.4.0"
        },
        "devDependencies": {
          "nodemon": "^3.0.0"
        }
      }
      ```
    - [ ] Add scripts:
      ```json
      {
        "scripts": {
          "start": "node src/index.js",
          "dev": "nodemon src/index.js"
        }
      }
      ```

3. **Configuration Setup**
    - [ ] Create `config.js`:
        - Server port
        - Announced IP address
        - Worker settings
        - Router codecs (Opus only)
    - [ ] Create `.env` file for environment variables

4. **Basic Server Implementation**
    - [ ] Create HTTP server
    - [ ] Initialize WebSocket server
    - [ ] Add connection handler (log only)
    - [ ] Implement graceful shutdown

5. **mediasoup Initialization**
    - [ ] Create mediasoup Worker
    - [ ] Create Router with Opus codec:
      ```javascript
      const mediaCodecs = [
        {
          kind: 'audio',
          mimeType: 'audio/opus',
          clockRate: 48000,
          channels: 2
        }
      ];
      ```
    - [ ] Log Worker and Router creation

6. **Logging Infrastructure**
    - [ ] Setup Winston or Pino logger
    - [ ] Log levels: debug, info, warn, error
    - [ ] Log to console and file
    - [ ] Timestamp all logs

---

## 🔗 PHASE 1: Signaling Layer (No Media)

### Goals
- WebSocket connection established Android ↔ Server
- Basic Join Protocol implemented
- No WebRTC media yet

### Tasks

#### 📱 Android Tasks

1. **WebSocket Client Implementation**
    - [ ] Create `SignalingClient.kt`:
        - OkHttp WebSocket client
        - Automatic reconnection logic
        - Connection states: Disconnected, Connecting, Connected, Error

2. **Message Protocol (Models)**
    - [ ] Create `SignalingMessage.kt` data classes:
        - `Join(roomId, peerId)`
        - `RequestCapabilities`
        - `ResponseCapabilities` (to store router RTP info)

3. **ViewModel Layer**
    - [ ] Create `ConnectionViewModel.kt`:
        - Handle user tapping "Connect"
        - Trigger `SignalingClient.connect()`
        - Send "join" and "getRouterRtpCapabilities" requests

4. **UI Updates**
    - [ ] Enable "Connect" button
    - [ ] Display connection status
    - [ ] Show received message logs in UI

5. **Error Handling**
    - [ ] Handle server unavailable or network drops

#### 🖥️ Server Tasks

1. **Signaling Implementation**
    - [ ] Implement `handleJoin()`: Track client in memory
    - [ ] Implement `handleGetRouterRtpCapabilities()`: Return `router.rtpCapabilities`

2. **Logging**
    - [ ] Log all incoming/outgoing signals for debugging

---

## 🎤 PHASE 2: WebRTC Engine Boot (Local Audio Only)

### Goals
- WebRTC PeerConnectionFactory initialized
- Microphone access working
- AudioTrack created (Local only)

### Tasks (Android)

1. **WebRTC Initialization**
    - [ ] Initialize `PeerConnectionFactory` in `WebRtcEngine.kt`
    - [ ] Create `JavaAudioDeviceModule` (Disable EC/NS/AGC for DJ quality)

2. **Audio Track Creation**
    - [ ] Create local `AudioSource` with DJ-grade constraints
    - [ ] Create `AudioTrack` and enable it

3. **Permissions**
    - [ ] Implement runtime `RECORD_AUDIO` request logic

---

## 📤 PHASE 3: mediasoup SendTransport + Produce Audio

### Goals
- Android creates PeerConnection for mediasoup
- Android sends real Opus audio RTP to server

### Tasks

#### 📱 Android Tasks

1. **MediaSoup Client Logic**
    - [ ] Load `device` with stored `routerRtpCapabilities`
    - [ ] Request `createWebRtcTransport` from server
    - [ ] Create local `PeerConnection` using server's ICE/DTLS params

2. **Transport Connection**
    - [ ] Implement `onConnect` callback: Send local DTLS parameters to server

3. **Produce Message**
    - [ ] Implement `onProduce` callback: Send local `rtpParameters` to server
    - [ ] Receive and store `producerId`

4. **Monitoring**
    - [ ] Display bitrate and RTT in UI

#### 🖥️ Server Tasks

1. **Transport Creation**
    - [ ] Implement `router.createWebRtcTransport()` handler
    - [ ] Return id, iceParameters, iceCandidates, dtlsParameters

2. **Producer Handler**
    - [ ] Implement `transport.produce()` using client RTP parameters
    - [ ] Verify RTP packet flow in server logs

---

## 🎧 PHASE 4: Studio Consumer (Windows Receiver MVP)

### Goals
- Studio receives DJ audio in real-time
- Audio plays with <250ms latency

(Remaining Phases 4-7 follow the same logic: Android is consumer/transmitter, server is orchestrator)

---

## 🚦 Progress Tracker

```
[ ] Phase 0: Foundation (Build passes)
[ ] Phase 1: Signaling (WebSocket Join working)
[ ] Phase 2: WebRTC Engine (Mic active locally)
[ ] Phase 3: mediasoup Transport (Audio reaches server)
[ ] Phase 4: Studio Consumer (Studio hears DJ)
[ ] Phase 5: FM Integration (Audio in broadcast chain)
[ ] Phase 6: Stability (2-hour test passes)
[ ] Phase 7: Production (Deployed)
```

---

## 🎯 MVP Feature Checklist

### Android App ✅
- [ ] WebSocket Join
- [ ] RTP Capabilities sync
- [ ] Foreground Service broadcast
- [ ] Manual SendTransport/Producer logic
- [ ] UI Stats (Bitrate/Loss)

### Server ✅
- [ ] Room/Peer state management
- [ ] mediasoup WebRtcTransport creation
- [ ] Producer orchestration
- [ ] Signaling bridge

---

**Version**: 1.1 (Client-Centric Fix)
**Last Updated**: 2026-01-29
