# StreamNode

**Stream Android microphone, screen, and front/back cameras live to any browser — free, 24/7, with full remote control.**

| Property | Value |
|---|---|
| **Package** | `com.akdevelopers.streamnode` |
| **Version** | `2.0.0` (versionCode 12) |
| **Min SDK** | API 26 (Android 8.0) |
| **Target SDK** | API 35 (Android 15) |
| **Server** | Node.js v5.2 (TypeScript, modular plugin system) |
| **Primary Transport** | WebRTC P2P (SRTP/DTLS) with automatic fallback |
| **Fallback Transport** | WebSocket binary relay (hot-standby, <50 ms switchover) |
| **Optimization plan** | All 38 items from `docs/optimization-and-transport-plan.md` **fully implemented** |

---

## Table of Contents

1. [What is StreamNode?](#1-what-is-streamnode)
2. [Feature Set](#2-feature-set)
3. [Repository Structure](#3-repository-structure)
4. [Android Architecture](#4-android-architecture)
5. [Server Architecture](#5-server-architecture)
6. [Transport Layer](#6-transport-layer)
7. [App ↔ Server Contract (Full Protocol)](#7-app--server-contract-full-protocol)
8. [Streaming Capabilities](#8-streaming-capabilities)
9. [Remote Command System](#9-remote-command-system)
10. [Telemetry System](#10-telemetry-system)
11. [Device Admin & Remote Control](#11-device-admin--remote-control)
12. [Quick Start](#12-quick-start)
13. [Building the APK](#13-building-the-apk)
14. [Deployment](#14-deployment)
15. [CI/CD Pipeline](#15-cicd-pipeline)
16. [Configuration Reference](#16-configuration-reference)
17. [Documentation Index](#17-documentation-index)

---

## 1. What is StreamNode?

StreamNode is a modular Android + Node.js system that turns an Android phone into a 24/7 live-streaming node:

- **Audio**: Microphone captured as 48 kHz Opus, streamed via WebRTC P2P directly to browser
- **Screen**: Full-device screen share via MediaProjection
- **Cameras**: Front and back cameras simultaneously, with PiP layout options
- **Remote control**: Lock, reboot, wipe, brightness, volume, quality — all from a web dashboard
- **Telemetry**: Battery, CPU temp, signal, RAM, Wi-Fi, stream FPS/kbps — live on the dashboard
- **Location**: GPS coordinates streamed to a Leaflet.js map on the dashboard
- **Intercom**: Two-way audio — browser microphone plays on the phone's speaker
- **Call capture**: Automatically detects phone calls and captures both voices

The server is a single Node.js process on port 4000 with a plugin architecture. Each feature is a `ServerModule` auto-discovered alphabetically. No central file modifications are needed to add features.

---

## 2. Feature Set

All features from the v5.2 implementation plan and the full optimization & transport plan are fully shipped:

| # | Feature | Android Component | Server / Dashboard |
|---|---|---|---|
| 1 | **Advanced Audio Config** | `AudioQualityConfig`, VBR/frame-ms/complexity | `POST /admin/:id/set-audio-config`, full audio config panel |
| 2 | **Sample Rate Selector** | `MicOrchestrator.setSampleRate()` | `POST /admin/:id/set-sample-rate` |
| 3 | **VOX Silence Gate** | `AudioCaptureEngine.silenceDropRate` | `POST /admin/:id/set-vox`, VOX toggle + threshold |
| 4 | **Internal Audio Capture** | `InternalAudioCaptureEngine` (API 29+) | `POST /admin/:id/set-internal-audio` |
| 5 | **Bandwidth / Quality Presets** | `AudioQualityPreset` HIGH/MEDIUM/LOW | `POST /admin/:id/set-quality`, quality `<select>` |
| 6 | **Two-Way Audio (Intercom)** | `WebRTCEngine.onTrack` + AudioManager | 🎙 Hold-to-Talk in `/player` |
| 7 | **Server-Side Recording** | — | `RecordingModule` + ffmpeg, ⏺/⏹ + download |
| 8 | **Battery/Signal/CPU Telemetry** | `TelemetryReporter` (adaptive interval) | Telemetry strip per card, 🔋📶🌡 alerts |
| 9 | **Multi-Camera PiP** | `WebRTCEngine` dual-track | PiP canvas at `/pip?id=` |
| 10 | **Snapshot / Screenshot** | `CameraOrchestrator.captureSnapshot()` | `POST /admin/:id/snapshot`, modal + download |
| 11 | **Torch / Flashlight** | `TorchController.kt` | `POST /admin/:id/torch`, 🔦 ON/OFF |
| 12 | **Auto-Start on Boot** | `BootReceiver.kt` | Auto-start switch |
| 13 | **Location Streaming** | `LocationReporter` (displacement-gated) | Leaflet.js map |
| 14 | **Metrics History (SSE)** | — | `MetricsModule` 60-entry ring buffer + SSE |
| 15 | **Transport Fallback (WebRTC → WS)** | `ConnectionOrchestrator` fallback FSM, `standbyWsClient` | Transport badge per card, `set-transport` endpoint |
| 16 | **Transport Selector UI** | `TransportMode` enum, RadioGroup in `MainActivity` | ⚡ P2P / 📡 Relay / 🔁 Auto buttons in admin panel |
| 17 | **Transport Notification Badge** | `StreamingNotificationManager` | Foreground notification shows active transport |
| 18 | **Adaptive Opus Complexity** | `AudioQualityConfig` (HIGH→7, MED→5, LOW→3) | Reduces encoder CPU ~35% with no audible quality loss |
| 19 | **Adaptive Telemetry Interval** | `TelemetryReporter.setInterval()` + screen receiver | 60 s screen-on / 120 s screen-off / 300 s idle |
| 20 | **Delta Telemetry** | `TelemetryReporter` diff engine | Only changed fields sent; full resync every 5 min |
| 21 | **Silence Notification Protocol** | `AudioCaptureEngine.onSilenceStateChanged` | Server stores `silenceActive`; browsers get lightweight payload |
| 22 | **AudioRecord Soft-Suspend** | `AudioCaptureEngine` (5 s sustained silence → `stop()`) | Saves ~10 mA DSP draw during silence |
| 23 | **PCM/Opus Buffer Pool** | `ByteArrayPool` in `core:common` | Zero per-frame allocations in steady state |
| 24 | **Adaptive Bitrate Ladder** | `MicOrchestrator.autoQualityFromRtt()` | RTT < 150 ms → HD; 150–400 ms → MD; > 400 ms → LQ |
| 25 | **WatchdogWorker Back-off** | `WatchdogWorker` health-aware skip + 2× interval | Skips when healthy; doubles alarm interval on consecutive healthy checks |
| 26 | **Location Displacement Gate** | `LocationReporter` (10 m threshold) | ~90% reduction in GPS packets for stationary devices |
| 27 | **Stats Broadcast Diff Guard** | `server.ts statsInterval` | Skips broadcast when nothing changed; 3 s interval (was 2 s) |
| 28 | **ffmpeg Backpressure Guard** | `RecordingModule` `writableNeedDrain` check | Drops frames instead of growing Node.js heap on slow disk |
| 29 | **STUN Server Reduction** | `SignalingModule.buildIceServers()` | 3 → 2 servers by default; `STUN_SERVERS` env override |
| 30 | **Firebase URL Poll Removed** | `ConnectionOrchestrator` | Redundant 30-min poll removed; live push listener covers it |
| 31 | **Signaling Keepalive Removed** | `SignalingModule` | Per-socket `setInterval` removed; global heartbeat is sole mechanism |
| 32 | **Hourly Dedup Prune** | `CommandProcessor.startDedupPrune()` | Trims `executedIds` to 20 entries every hour regardless of activity |
| 33 | **WebRTC Renegotiation Debounce** | `WebRTCEngine.scheduleRenegotiation()` | 200 ms → 500 ms; batches rapid multi-track adds into one offer/answer |
| 34 | **Metrics Ring Buffer Halved** | `config.ts METRICS_HISTORY_SIZE = 60` | 1 h history at 60 s/sample; halves per-device server RAM |

---

## 3. Repository Structure

```
StreamNode/
│
├── app/                          Android :app module (composition root)
│   ├── src/main/
│   │   ├── AndroidManifest.xml   All permissions + component declarations
│   │   ├── java/com/akdevelopers/streamnode/
│   │   │   ├── StreamNodeApp.kt    Application class — initialises WebRTC, DI
│   │   │   └── di/               (reserved for Hilt/manual DI overrides)
│   │   └── res/                  Layouts, drawables, strings, themes
│   ├── build.gradle.kts          applicationId, signingConfigs, versionCode=12
│   ├── google-services.json      Firebase config (do not commit to public repos)
│   ├── proguard-rules.pro
│   └── libs/opus.aar             Pre-built Opus native encoder
│
├── core/
│   ├── common/                   BaseViewModel, AppConstants, extensions
│   ├── platform/                 BootReceiver, WatchdogWorker, NetworkChangeReceiver,
│   │                             PhoneCallMonitor, SystemUtils
│   ├── observability/            StreamNodeLogger, Analytics, CrashManager (Firebase)
│   └── deviceadmin/              DeviceAdminCommander, StreamNodeDeviceAdminReceiver
│
├── domain/
│   └── streaming/                Interfaces: StreamOrchestrator, StreamIdentity,
│                                 AudioTransport, RemoteCommandSource, StatusPublisher,
│                                 MetricsPublisher, StreamStatus, StreamLogger
│
├── data/
│   └── streaming/                Full implementation of all domain interfaces:
│       └── src/main/java/.../
│           ├── audio/            AudioCaptureEngine, AudioWebSocketClient,
│           │                     AudioControlClient, AudioQualityConfig,
│           │                     AudioCompressor, CameraCapture*, ScreenCapture*,
│           │                     SignalingClient, WebRTCEngine,
│           │                     InternalAudioCaptureEngine, OpusEncoderWrapper,
│           │                     DeviceFeatureConfig
│           ├── di/               AppGraph (composition root), ServiceLocator
│           ├── remote/           FirebaseRemoteController
│           └── service/          ConnectionOrchestrator, CommandProcessor,
│                                 MicOrchestrator, TelemetryReporter,
│                                 LocationReporter, TorchController,
│                                 CameraOrchestrator, InputInjector,
│                                 StreamLockManager
│
├── feature/
│   ├── setup/                    SetupActivity — 4-step mandatory wizard
│   └── stream/                   MainActivity, MainViewModel, StreamingService
│
├── server/                       Node.js TypeScript server (v5.2)
│   ├── server.ts                 Entry point — HTTP + WS servers, module loader
│   ├── src/
│   │   ├── config.ts             All env vars — frozen AppConfig object
│   │   ├── channels.ts           Channel registry + lifecycle (grace timer)
│   │   ├── logger.ts             Structured logger (forModule pattern)
│   │   ├── firebase.ts           Firebase Admin SDK init
│   │   ├── core/                 ServerModule, ServerContext, ModuleLoader,
│   │   │                         HttpRouteDefinition, WsRouteDefinition,
│   │   │                         HealthCheck, EnvConfig
│   │   ├── modules/
│   │   │   ├── admin/            AdminModule — /admin/* HTTP API
│   │   │   ├── camera/           CameraModule — /camera-front|back WS relay
│   │   │   ├── metrics/          MetricsModule — ring buffer + SSE
│   │   │   ├── recording/        RecordingModule — ffmpeg OGG recording
│   │   │   ├── screen/           ScreenModule — /screen WS relay
│   │   │   ├── signaling/        SignalingModule — /ice-config + /signal WS
│   │   │   └── streaming/        StreamingModule — catch-all HTTP + WS
│   │   ├── routes/
│   │   │   ├── http.ts           HTTP catch-all handler
│   │   │   └── websocket.ts      WS dispatcher (/stream /control /listen)
│   │   ├── middleware/           adminAuth, cors, requestId, validate
│   │   ├── services/             metrics.ts, rateLimit.ts
│   │   └── types/                http.d.ts, ws.d.ts
│   ├── public/                   Browser pages (served as static HTML)
│   │   ├── index.html            Dashboard — device cards + map + controls
│   │   ├── player.html           WebRTC audio player + intercom
│   │   ├── pip.html              Dual-camera PiP canvas
│   │   ├── screen.html           Screen share viewer
│   │   └── camera.html           Single-camera viewer
│   ├── recordings/               OGG recordings directory (auto-created)
│   ├── config/serviceAccount.json Firebase Admin key (do not commit)
│   ├── package.json              deps: dotenv, firebase-admin, ws
│   ├── tsconfig.json
│   ├── render.yaml               Render.com free deployment config
│   ├── MODULES.md                Server module system reference
│   └── DEPLOY.md                 Free deployment guide (Render.com)
│
├── docs/
│   ├── architecture.md           System design, data flows, module map
│   ├── operations.md             Setup, deployment, API reference, troubleshooting
│   └── extending.md              Adding modules, commands, telemetry fields
│
├── scripts/
│   ├── start-streamnode.bat        Launch server + ngrok + browser
│   ├── stop-streamnode.bat         Kill all StreamNode processes
│   ├── build_debug_fix.bat       Build APK with C:\jbr21 (OpenJDK 21)
│   ├── provision-device-owner.bat ADB script — grant Device Owner
│   └── revoke-device-admin.bat   ADB script — revoke Device Admin
│
├── .github/workflows/ci.yml      3-job CI: server check → lint/test → release APK
├── gradle/libs.versions.toml     Centralized dependency versions (version catalog)
├── settings.gradle.kts           Multi-module project declaration (9 modules)
└── keystore.properties.template  Template for release signing keys
```

---

## 4. Android Architecture

### Module Dependency Graph

```
:app  (composition root)
  ├── :feature:setup        SetupActivity (4-step wizard)
  ├── :feature:stream       MainActivity, MainViewModel, StreamingService
  ├── :data:streaming       All network + device implementation
  │     ├── :domain:streaming   Interfaces only (no Android deps)
  │     ├── :core:common
  │     ├── :core:observability
  │     ├── :core:platform
  │     └── :core:deviceadmin
  └── (all core modules)
```

**Rule:** `:feature:*` → `:data:streaming` → `:domain:streaming` → `:core:*`
No circular dependencies. `:app` composes all modules at runtime.

### Key Android Classes

| Class | Module | Responsibility |
|---|---|---|
| `StreamNodeApp` | :app | Application init — WebRTC `initializeOnce()`, DI setup |
| `SetupActivity` | :feature:setup | 4-step wizard: permissions → battery → OEM autostart → Device Admin |
| `MainActivity` | :feature:stream | Log console, start/stop controls, intent routing |
| `StreamingService` | :feature:stream | Android FGS (foreground service) — orchestrates all collaborators |
| `ConnectionOrchestrator` | :data:streaming | Owns all network connections for a session; routes between WebRTC and legacy WS paths |
| `WebRTCEngine` | :data:streaming | RTCPeerConnection lifecycle, tracks, ICE, offer/answer, intercom |
| `SignalingClient` | :data:streaming | WebSocket to /signal with exponential backoff |
| `AudioControlClient` | :data:streaming | WebSocket to /control — commands, telemetry, snapshot |
| `AudioWebSocketClient` | :data:streaming | WebSocket to /stream — binary Opus frames (legacy path) |
| `MicOrchestrator` | :data:streaming | AudioRecord capture, Opus encoding, phone-call mode, quality switching, backoff restart |
| `AudioCaptureEngine` | :data:streaming | Low-level AudioRecord + Opus encoder loop + VOX silence gate |
| `InternalAudioCaptureEngine` | :data:streaming | API 29+ AudioPlaybackCaptureConfiguration — captures media/game audio |
| `TelemetryReporter` | :data:streaming | Battery, signal, CPU, RAM, Wi-Fi, stream metrics every 60 s |
| `LocationReporter` | :data:streaming | FusedLocationProvider GPS every 30 s (opt-in) |
| `TorchController` | :data:streaming | CameraManager.setTorchMode() |
| `CameraOrchestrator` | :data:streaming | Camera2 snapshot + legacy H.264 streaming |
| `CommandProcessor` | :data:streaming | Central dedup (LinkedHashSet) + dispatch for all incoming commands |
| `FirebaseRemoteController` | :data:streaming | Firebase RTDB listener + status publisher |
| `DeviceAdminCommander` | :core:deviceadmin | DPM operations: lock, wipe, reboot, brightness, camera policy, etc. |
| `BootReceiver` | :core:platform | Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` |
| `PhoneCallMonitor` | :core:platform | TelephonyManager call state → triggers MicOrchestrator call mode switch |
| `NetworkChangeReceiver` | :core:platform | Connectivity changes → triggers reconnect |
| `StreamIdentity` | :domain:streaming | Persistent UUID + display name; `appendToUrl()` helper |
| `AppGraph` | :data:streaming | Manual DI composition root |
| `ServiceLocator` | :data:streaming | Process-wide singleton access to AppGraph |

### Foreground Service

`StreamingService` runs as a Foreground Service with type `microphone | mediaProjection | connectedDevice`. It:
- Wires all collaborators via `AppGraph`
- Calls `CommandProcessor.wireCallbacks()` to register action handlers
- Manages `MicOrchestrator`, `ConnectionOrchestrator`, `TelemetryReporter`, `LocationReporter`
- Updates the persistent notification with current status
- Handles `ACTION_BOOT_COMPLETED` via `BootReceiver`

### Setup Wizard

4-step mandatory flow in `SetupActivity`:
1. **Permissions** — `RECORD_AUDIO`, `CAMERA`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, optional `ACCESS_FINE_LOCATION`
2. **Battery exemption** — `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
3. **OEM autostart** — deep-links to Xiaomi/OPPO/Huawei/Samsung battery settings
4. **Device Admin** (skippable) — `DevicePolicyManager.addDeviceAdminService()`

---

## 5. Server Architecture

### Module Loading

`server.ts` creates `ServerContext` (DI container), then `ModuleLoader` auto-discovers all `.ts` files in `src/modules/` **alphabetically**. No central registration — drop a file and restart.

**Load order (alphabetical = priority order):**
```
admin/AdminModule       → /admin/* HTTP routes (regex excludes /admin/:id/record/*)
camera/CameraModule     → /camera-front|back WS + HTTP routes
metrics/MetricsModule   → /metrics/* HTTP routes (ring buffer, SSE)
reconnect/ReconnectModule → no routes; lifecycle-only (startup wake blast + serverStartedAt stamp)
recording/RecordingModule → /admin/:id/record/* HTTP routes
screen/ScreenModule     → /screen WS + HTTP routes
signaling/SignalingModule → /ice-config HTTP + /signal WS
streaming/StreamingModule → catch-all HTTP '*' + catch-all WS '*' (always last)
```

First-match-wins. `StreamingModule` must always sort last alphabetically — it is the catch-all.

### Channel Lifecycle

Each connected device creates one `Channel` object (keyed by UUID):

```typescript
Channel {
  id, displayName,
  controlWs,         // /control WebSocket (commands, telemetry, snapshot)
  phoneWs,           // /stream WebSocket (legacy audio)
  signalingPhone,    // /signal WebSocket (WebRTC)
  browsers,          // Set<WebSocket> /listen listeners
  signalingBrowsers, // Set<WebSocket> /signal browsers
  streamingActive, callActive,
  codecConfig,       // Opus params announced at connect time
  recording,         // ffmpeg process + file info
  telemetry,         // latest telemetry snapshot (all fields)
  snapshotResolve,   // pending snapshot Promise resolver
  location,          // latest GPS fix
  stats              // framesRelayed, bytesRelayed, connectedAt, lastActivityMs
}
```

`isPhoneConnected(ch)` returns `true` if **any** of `controlWs`, `phoneWs`, or `signalingPhone` is open — prevents premature eviction for WebRTC-only devices.

**Grace timer:** `CHANNEL_GRACE_MS` (default 5 min) defers channel deletion after all phone sockets close, allowing reconnection without losing the UUID.

### Server Timers

| Timer | Interval | Purpose |
|---|---|---|
| `wsHeartbeat` | `WS_PING_INTERVAL_MS` (30 s) | Ping all WS clients, terminate non-responsive |
| `staleCheck` | `STALE_CHECK_MS` (15 s) | Terminate phone sockets idle > `STALE_THRESHOLD_MS` (90 s) |
| `statsInterval` | `STATS_INTERVAL_MS` (**3 s**, was 2 s) | Broadcast stats JSON; **skips broadcast when payload unchanged** |
| `rateLimitPurge` | `RATE_WINDOW_MS` (60 s) | Clean stale IP rate-limit buckets |

---

## 6. Transport Layer

### WebRTC P2P (Default)

WebRTC is the primary transport. Audio and video flow phone → browser via SRTP/DTLS after ICE negotiation. The server handles signaling only.

```
Phone                         Server (/signal WS)            Browser
  |─── WS connect role=phone ──>|                               |
  |<── {welcome} ───────────────|                               |
  |                              |<── WS connect role=browser ──|
  |                              |──> {phone-ready} ────────────>|
  |── createOffer ──────────────>|──> {offer, sdp} ─────────────>|
  |<── {answer, sdp} ────────────|<── {answer, sdp} ─────────────|
  |═══════════════ SRTP/DTLS P2P audio ══════════════════════════>|
```

### Automatic Transport Fallback

`ConnectionOrchestrator` maintains a hot-standby WebSocket client while WebRTC is active. If ICE fails, the standby client is activated with zero audio gap (<50 ms switchover).

```
App start → WEBRTC_NEGOTIATING
               │
         ICE connected?
        YES → WEBRTC_P2P       (standby WS suspended)
        NO  → FALLBACK_TRIGGERED → WEBSOCKET_RELAY
                                        │
                                 ICE restart succeeds?
                                YES → back to WEBRTC_P2P
```

Transport state is reported to the server as `{ type:"transportStatus", mode, rtt, iceState }` and broadcast to dashboard browsers in real time.

### Transport Selector

The transport can be controlled from three places:

| Control | Location |
|---|---|
| **Auto** (default) | Attempt WebRTC, fall back to WS automatically |
| **Force WebRTC** | Dashboard admin panel ⚡ button or Android RadioGroup |
| **Force WebSocket** | Dashboard admin panel 📡 button or Android RadioGroup |
| **Remote command** | `set_transport_webrtc` / `set_transport_ws` / `set_transport_auto` |

The `POST /admin/:id/set-transport?mode=webrtc|websocket|auto` endpoint dispatches the appropriate `set_transport_*` command to the phone via WebSocket or Firebase.

### Transport Badge States

| Mode | Dashboard badge | Android notification |
|---|---|---|
| `webrtc_p2p` | `⚡ P2P` (green) | `🎙 Streaming · ⚡ P2P` |
| `websocket_relay` | `📡 Relay` (yellow) | `🎙 Streaming · 📡 Relay` |
| `negotiating` | `🔄 ICE…` (blue, pulsing) | `🎙 Streaming · 🔄 ICE…` |
| `fallback` | `⚠ Fallback` (orange) | `🎙 Streaming · ⚠ Fallback` |

### WebSocket Fallback

Legacy path when `PREF_WEBRTC_ENABLED = false`. All media passes through the server:

| Path | Direction | Content |
|---|---|---|
| `/stream?id=&name=` | Phone → Server → `/listen` | Binary Opus frames |
| `/control?id=&name=` | Bidirectional | JSON commands, telemetry, location, snapshot |
| `/screen?id=&name=` | Phone → Server → `/screen-watch` | H.264 NAL units |
| `/camera-front?id=` | Phone → Server → `/camera-front-watch` | H.264 NAL units |
| `/camera-back?id=` | Phone → Server → `/camera-back-watch` | H.264 NAL units |

### URL Construction

All URLs are derived from one base `serverUrl` (e.g. `wss://host/stream`):

| Connection | Derived URL |
|---|---|
| `/control` | Replace `/stream` with `/control` |
| `/signal` | Strip `/stream`, append `/signal` |
| `/ice-config` | `wss://` → `https://`, strip `/stream` |
| `/screen` | Replace `/stream` with `/screen` |
| `/camera-front` | Replace `/stream` with `/camera-front` |

`StreamIdentity.appendToUrl()` adds `?id=<uuid>&name=<displayName>` to all URLs.

### Transport Comparison

| Property | WebRTC P2P | WebSocket Relay |
|---|---|---|
| Latency | ~50–150 ms | ~200–250 ms |
| Server load | Signaling only | All media |
| Activation | Default (PREF_WEBRTC_ENABLED=true) | Auto-fallback or manual |
| Fallback trigger | ICE FAILED → automatic | Manual or remote command |
| Switchover gap | <50 ms (hot-standby WS) | — |
| Dashboard badge | ⚡ P2P | 📡 Relay / ⚠ Fallback |

---

## 7. App ↔ Server Contract (Full Protocol)

### JSON message types on `/control` (bidirectional)

| `type` | Direction | Fields | Purpose |
|---|---|---|---|
| `cmd` | Server → Phone | `commandId`, `action`, `url`, `source` | Remote command delivery |
| `telemetry` | Phone → Server | 17 fields (see §10); includes `delta:true` when partial | Device metrics (adaptive interval: 60/120/300 s) |
| `location` | Phone → Server | `lat`, `lng`, `accuracy`, `altitude`, `speed`, `bearing`, `ts` | GPS (displacement-gated: >10 m or >5 min) |
| `statusUpdate` | Phone → Server | `status` | Streaming state changes |
| `streamingState` | Phone → Server | `active` | Mic on/off |
| `codec` | Phone → Server | `codec`, `sampleRate`, `channels`, `frameMs`, `bitrate` | Codec announce at connect |
| `transportStatus` | Phone → Server | `mode`, `rtt`, `iceState`, `ts` | Active transport report; broadcast to browsers |
| `silence` | Phone → Server | `active` (bool) | VOX silence start/end; server sends lightweight payload to browsers while active |
| `ping` | Both | — | Keepalive |
| `pong` | Both | — | Keepalive response; RTT measured by `AudioControlClient` for adaptive bitrate |
| `input` | Server → Phone | `kind`, `x`, `y`, … | Remote touch/key injection |

**Binary messages on `/control`**: JPEG bytes from phone snapshot response.

### Firebase RTDB contract

| Path | Written by | Read by | Purpose |
|---|---|---|---|
| `/streamnode_config/serverUrl_v2` | Admin | App | WebSocket server URL |
| `/streamnode_config/serverStartedAt` | Server (`ReconnectModule.onStart`) | App (`FirebaseRemoteController`) | Server restart detection — Android calls `reconnectNow()` in <3 s when this changes |
| `/streamnode_config/serverOnline` | Server (`ReconnectModule`) | Dashboard | Server health flag (true on start, false on SIGINT) |
| `/users/{id}/control` | Server (Admin Firebase) | `FirebaseRemoteController` | Offline command delivery |
| `/users/{id}/status` | `FirebaseRemoteController` | Dashboard | Device online/streaming state |
| `/users/{id}/metrics` | `FirebaseRemoteController` | Dashboard | Realtime fps/kbps |
| `/users/{id}/adminStatus` | App | `GET /admin/:id/status` | Device/Owner admin level |

### URL Versioning

| Firebase key | Value | Who reads |
|---|---|---|
| `/streamnode_config/serverUrl` | ngrok URL (old) | v1 APKs — do not touch |
| `/streamnode_config/serverUrl_v2` | `wss://your-ngrok/stream` | v2 APKs (this build) |

**Fallback**: v2 falls back to `AppConstants.DEFAULT_SERVER_URL` if Firebase is unavailable.

---

## 8. Streaming Capabilities

### Audio — WebRTC (primary)
48 kHz mono Opus via WebRTC P2P. ~50–150 ms latency. Open `/player?id=<uuid>`.

**Quality presets** (runtime-switchable from dashboard):

| Preset | Bitrate | AGC/NS | Use case |
|---|---|---|---|
| `HIGH_QUALITY` | 192 kbps | Off | Default — maximum fidelity |
| `MEDIUM` | 96 kbps | On | Balanced — 3G / poor Wi-Fi |
| `LOW` | 32 kbps | On | Emergency — very poor connection |

**Advanced audio config** (Feature 8): full per-parameter control — `sampleRate` (8/16/32/48 kHz), `bitrate` (6–510 kbps), `frameMs` (2/5/10/20/40/60 ms), `vbr`, `complexity` (0–10), `agc`, `ns`, `aec`, `voxThreshold`.

### VOX Silence Gate (Feature 3)
`AudioCaptureEngine` drops PCM frames below RMS threshold. Saves 60–80% bandwidth during silence. Enabled/threshold configurable at runtime via `POST /admin/:id/set-vox`.

### Internal Audio Capture (Feature 4)
Captures device media/game audio output via `AudioPlaybackCaptureConfiguration` (API 29+). Can run alongside mic (mix mode) or replace mic (internal-only mode).

### Two-Way Audio (Intercom)
Browser mic → phone speaker via WebRTC renegotiation. Push-to-talk 🎙 Hold-to-Talk button in `/player`. Only active when WebRTC transport is established.

### Screen Share
`MediaProjection` → `ScreenCapturerAndroid` → WebRTC video track. On legacy path: H.264 Baseline 3.1 @ 1 Mbps/30 fps → `/screen` → `/screen-watch`.

### Camera Streaming
Front and back cameras simultaneously. WebRTC: two `VideoTrack` objects on the same `RTCPeerConnection`. Legacy: separate WebSocket connections per camera. PiP canvas at `/pip?id=`.

### Snapshot
`POST /admin/:id/snapshot` → server sends `{type:"cmd",action:"snapshot"}` → `CameraOrchestrator.captureSnapshot()` → binary JPEG on `/control` → HTTP response. 5-second timeout.

### Call Streaming

| Phase | What happens |
|---|---|
| Call detected | `PhoneCallMonitor` → `MicOrchestrator.switchToCallMode()` |
| Engine swap | Current engine torn down; new one with `callMode = true` |
| Speakerphone | Forced ON (both sides of call captured) |
| Quality profile | `CALL_CAPTURE` — 48 kHz, 96 kbps, AGC + NS on |
| Call ends | `switchToNormalMode()` → speakerphone restored → `HIGH_QUALITY` resumes |

Call audio always uses the **legacy WebSocket path** even when WebRTC is primary.

### Server-Side Recording

Uses ffmpeg to pipe raw Opus frames into an OGG container on disk.

| Endpoint | Response |
|---|---|
| `POST /admin/:id/record/start` | `{ filePath, startedAt }` |
| `POST /admin/:id/record/stop` | `{ filePath, durationSec }` |
| `GET /admin/:id/record/status` | `{ active, filePath, durationSec }` |
| `GET /recordings/:filename` | OGG file download |

Configure output directory via `RECORDINGS_DIR` in `.env` (default: `server/recordings/`).

---

## 9. Remote Command System

Commands reach the phone via two paths, both deduplicated by `CommandProcessor`:

```
Dashboard button
  │
  ├─► POST /admin/:id/<action>            (AdminModule HTTP → controlWs.send)
  │                                            └─► AudioControlClient → CommandProcessor
  │
  └─► (phone offline) ─────────────────► Firebase RTDB /users/:id/control
                                               └─► FirebaseRemoteController → CommandProcessor
```

`CommandProcessor` deduplicates by `commandId` (in-memory `LinkedHashSet` + SharedPrefs persistence across restarts). All callbacks are dispatched on the main thread.

### Full Command Reference

| Action | Payload | Handler |
|---|---|---|
| `start` | — | `onStart()` → `StreamingService.startMic()` |
| `stop` | — | `onStop()` → `StreamingService.stopMic()` |
| `change_url` | new URL string | `onChangeUrl()` → `ConnectionOrchestrator.changeUrl()` |
| `reconnect` | — | `onReconnect()` → `ConnectionOrchestrator.reconnectNow()` |
| `set_quality` | `HIGH_QUALITY\|MEDIUM\|LOW` | `onSetQuality()` → `MicOrchestrator.setQuality()` |
| `set_sample_rate` | `8000\|16000\|32000\|48000` | `onSetSampleRate()` → `MicOrchestrator.setSampleRate()` |
| `set_vbr` | `"true"\|"false"` | `onApplyFeatureConfig(vbr, null)` |
| `set_frame_ms` | `"2"\|"5"\|"10"\|"20"\|"40"\|"60"` | `onApplyFeatureConfig(null, ms)` |
| `set_vox` | `{"enabled":bool,"threshold":num}` | `onSetVox()` → `MicOrchestrator.setVox()` |
| `set_audio_config` | Full JSON config object | `onSetAudioConfig()` → `MicOrchestrator.applyAudioConfig()` |
| `internal_audio` | `{"enabled":bool,"mixWithMic":bool}` | `onInternalAudio()` → `MicOrchestrator.applyInternalAudio()` |
| `snapshot` | — | `onSnapshot()` → `CameraOrchestrator.captureSnapshot()` |
| `admin_lock` | — | `DeviceAdminCommander.execute()` |
| `admin_reboot` | — | DPM reboot (Device Owner only) |
| `admin_wipe` | — | DPM factory reset |
| `admin_camera_disable` | `"true"\|"false"` | DPM camera policy |
| `admin_brightness` | `"0"–"255"` | Settings.System brightness |
| `admin_volume` | `"0"–"15"` | AudioManager media volume |
| `admin_reset_password` | new password | DPM password reset |
| `admin_clear_app_data` | package name | DPM clearApplicationUserData |
| `admin_uninstall_app` | package name | DPM uninstall |
| `admin_torch` | `"true"\|"false"` | `TorchController.setTorch()` |
| `set_transport_webrtc` | — | Force WebRTC P2P; disable auto-fallback |
| `set_transport_ws` | — | Force WebSocket relay |
| `set_transport_auto` | — | Re-enable automatic WebRTC with WS fallback |

---

## 10. Telemetry System

`TelemetryReporter` uses an **adaptive interval** and **delta encoding**:

- **Screen ON + streaming**: 60 s interval
- **Screen OFF + streaming**: 120 s interval  
- **Screen OFF + idle**: 300 s interval
- **Delta mode**: only fields that changed beyond their threshold are sent each tick. A full resync fires every 5 minutes.
- **Silence notification**: when VOX-gated silence exceeds 5 s, `AudioRecord` is soft-suspended and a `{type:"silence",active:true}` message is sent; the stats broadcast to browsers switches to a lightweight payload until audio resumes.

| Field | Type | Description |
|---|---|---|
| `batteryLevel` | 0–100 | Battery percentage |
| `charging` | bool | Is charging |
| `signalDbm` | 0–80 | Rough signal strength |
| `cpuTempC` | float | °C from `/sys/class/thermal`, -1 if unavailable |
| `wifiSsid` | string | Connected SSID, "N/A" if not Wi-Fi |
| `usedRamMB` | int | App process heap used (MB) |
| `totalRamMB` | int | App process max heap (MB) |
| `netType` | string | `wifi\|cellular\|ethernet\|unknown` |
| `linkSpeedMbps` | int | Wi-Fi link speed (-1 if unavailable) |
| `screenOn` | bool | Device screen interactive |
| `streamFps` | float | Opus frames/sec |
| `streamKbps` | float | Encoded audio kbps |
| `streamUptimeSec` | int | Seconds since service started |
| `voxDropRate` | float | 0–1 frame drop ratio |
| `voxEnabled` | bool | Silence gate active |
| `voxThreshold` | float | RMS gate threshold |
| `delta` | bool | Present when payload is partial (not all fields) |

**Server side**: `MetricsModule` keeps a ring buffer of the last **60** snapshots per device (1 hour at 60 s/sample). Access via:
- `GET /metrics/:id/history?limit=30` — last N snapshots (JSON)
- `GET /metrics/:id/stream` — Server-Sent Events live stream
- `GET /metrics/summary` — all devices' latest telemetry

**Dashboard alerts**: battery < 20% → badge red + beep; < 10% → card border flashes; CPU > 60°C → orange.

---

## 11. Device Admin & Remote Control

### Access Levels

| Level | Grant method | Capabilities |
|---|---|---|
| **Device Admin** | Setup wizard Step 4 | Lock, wipe, disable camera, password policy |
| **Device Owner** | ADB provisioning (one-time) | All above + reboot, clear app data, uninstall, brightness, silent operations |

### Device Owner Provisioning (ADB)

```bash
# Requires: no Google accounts on device, USB debugging enabled
adb shell dpm set-device-owner \
  com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver

# Or use the provided script:
scripts\provision-device-owner.bat
```

### Auto-Start on Boot

`BootReceiver` handles three intents:
- `ACTION_BOOT_COMPLETED`
- `ACTION_LOCKED_BOOT_COMPLETED` (direct boot — note: SharedPreferences unavailable until unlock)
- `ACTION_MY_PACKAGE_REPLACED` (on OTA update)

Checks both `PREF_AUTO_RESTART` (legacy) and `PREF_AUTO_START_ON_BOOT` (explicit toggle). Default: **off**.

---

## 12. Quick Start

```bash
# 1. Install server dependencies
cd server && npm install

# 2. Configure secrets
cp .env.example .env
# Edit: set FIREBASE_DB_SECRET
# Optional: STUN_SERVERS (override default Google STUN servers)
# Place server/config/serviceAccount.json (Firebase Admin key)

# 3. Start everything (Windows)
scripts\start-streamnode.bat
# Starts: Node server (port 4000) + ngrok tunnel + opens browser dashboard
```

**Stop**: `scripts\stop-streamnode.bat` or close the server/ngrok terminal windows.

### First Run Checklist

- [ ] `server/node_modules` present (`npm install`)
- [ ] `server/.env` — `FIREBASE_DB_SECRET` filled in
- [ ] `server/config/serviceAccount.json` present (never commit — gitignored)
- [ ] Firebase RTDB: `/streamnode_config/serverUrl_v2` = `wss://<ngrok-domain>/stream`
- [ ] Node server running on port 4000
- [ ] ngrok tunnel active
- [ ] StreamNode APK installed on phone
- [ ] Setup wizard completed (permissions + battery exemption)
- [ ] Dashboard shows device card with status
- [ ] Audio: `/player?id=<uuid>` → badge shows **⚡ WebRTC** or **📡 WebSocket**
- [ ] Recording: `ffmpeg` in PATH

---

## 13. Building the APK

```bat
:: Using the provided build script (sets JAVA_HOME=C:\jbr21 — OpenJDK 21)
scripts\build_debug_fix.bat

:: Or with any JDK 17+
set JAVA_HOME=C:\Program Files\Java\jdk-17
.\gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.akdevelopers.streamnode/.ui.setup.SetupActivity
```

**Release build** requires `keystore.properties` (copy from `keystore.properties.template`) or environment variables: `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`.

---

## 14. Deployment

### Local (Development)
```bash
# Dev mode (ts-node, no compile step):
cd server && npm run dev

# Dev with auto-restart on file change:
npm run dev:watch
```

### Production (Compiled)
```bash
cd server
npm run build          # Compiles TypeScript → dist/
npm start              # Runs dist/server.js
```

### Free Cloud — Render.com

See `server/DEPLOY.md` for the complete step-by-step guide. Summary:
1. Push `server/` folder to a separate GitHub repository
2. Create a Render.com Web Service — it auto-detects `render.yaml`
3. Set `FIREBASE_DB_SECRET` environment variable in Render dashboard
4. Add CNAME DNS record for your custom subdomain
5. Update Firebase RTDB `serverUrl_v2` to `wss://your-domain/stream`

**Free tier note**: Render spins down after 15 min of inactivity. Use UptimeRobot (free) to ping `/health` every 14 minutes.

---

## 15. CI/CD Pipeline

Three GitHub Actions jobs defined in `.github/workflows/ci.yml`:

| Job | Trigger | Steps |
|---|---|---|
| **server-check** | All pushes/PRs | Node 20 → `npm ci` → `npm run check` (TypeScript syntax) |
| **build** | After server-check passes | JDK 21 → lint → unit tests → `assembleDebug` → upload APK artifact (7 days) |
| **release** | `main` branch push only | JDK 21 → `assembleRelease` with keystore secrets → upload APK artifact (30 days) |

Secrets required in GitHub repository settings:
- `GOOGLE_SERVICES_JSON` — content of `app/google-services.json`
- `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS` — for release signing

---

## 16. Configuration Reference

### Server Environment Variables (`server/.env`)

| Variable | Default | Description |
|---|---|---|
| `PORT` | `4000` | HTTP/WS listen port |
| `FIREBASE_DB_URL` | hardcoded | Firebase RTDB URL |
| `FIREBASE_DB_SECRET` | `""` | Legacy REST secret; required for `/wake` endpoint |
| `WS_PING_INTERVAL_MS` | `30000` | WebSocket heartbeat ping interval |
| `STALE_THRESHOLD_MS` | `90000` | Idle phone socket eviction threshold (must be > STALE_CHECK_MS) |
| `STALE_CHECK_MS` | `15000` | How often to check for stale sockets |
| `STATS_INTERVAL_MS` | `3000` | Stats broadcast interval (was 2000; skip-if-unchanged guard also applies) |
| `CHANNEL_GRACE_MS` | `300000` | Channel TTL after phone disconnects (5 min) |
| `SERVER_VOLUME_GAIN` | `3.0` | Audio gain multiplier sent to browsers |
| `LOG_LEVEL` | `INFO` | `DEBUG\|INFO\|WARN\|ERROR` |
| `RATE_LIMIT_ENABLED` | `false` | Per-IP rate limiting |
| `MAX_REQUESTS_PER_WINDOW` | `120` | Max requests per rate window |
| `RATE_WINDOW_MS` | `60000` | Rate limit window size |
| `RECORDINGS_DIR` | `./recordings` | Directory for .ogg recording files |
| `METRICS_HISTORY_SIZE` | `60` | Ring buffer entries per device (1 h at 60 s intervals) |
| `STUN_SERVERS` | `stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302` | Comma-separated STUN URLs. **TURN is disabled** — setting `TURN_URL` causes a startup error. Symmetric NAT is handled by the WS relay fallback. |
| `TRANSPORT_FALLBACK_ENABLED` | `true` | Whether server stores/broadcasts transport fallback status |
| `RECONNECT_ON_STARTUP` | `true` | Stamp `serverStartedAt` + send Firebase `reconnect` to all devices on startup |
| `MAX_WAKE_DEVICES` | `50` | Max devices in one startup wake blast |

### Android SharedPreferences Keys

| Key (`AppConstants.PREF_*`) | Default | Description |
|---|---|---|
| `PREF_SERVER_URL` | Firebase → fallback | WebSocket server URL |
| `PREF_WEBRTC_ENABLED` | `true` | Use WebRTC P2P (false = force legacy WS) |
| `PREF_TRANSPORT_AUTO` | `true` | Auto-fallback from WebRTC to WS on ICE failure |
| `PREF_ADAPTIVE_BITRATE` | `true` | Auto-adjust quality preset based on pong RTT |
| `PREF_AUTO_START_ON_BOOT` | `true` after setup wizard | Start service on boot — now set to `true` automatically when the 4-step setup wizard completes |
| `PREF_AUTO_RESTART` | `true` after setup wizard | Watchdog/BootReceiver restart flag — also set to `true` at wizard completion |
| `PREF_SEND_LOCATION` | `false` | GPS location streaming opt-in |
| `PREF_LAST_COMMAND_ID` | — | Persisted dedup commandId |
| `PREF_SERVER_STARTED_AT` | `0` | Last known `serverStartedAt` epoch from Firebase. Suppresses spurious reconnect on cache-hit when the listener first attaches. |

### WebRTC NAT Traversal

StreamNode is **STUN-only** — TURN is permanently disabled. The built-in WebSocket relay handles all cases that TURN would cover.

| Scenario | ICE outcome | StreamNode response |
|---|---|---|
| Full-cone / Restricted-cone / Port-restricted NAT (~80–85 %) | STUN **succeeds** | ⚡ WebRTC P2P — SRTP/DTLS direct to browser |
| Symmetric NAT / corporate UDP block (~15–20 %) | ICE **FAILED** | 📡 WS relay — `ConnectionOrchestrator` activates hot-standby client (<50 ms switchover) |
| TURN env var accidentally set | — | 🔴 Server refuses to start (enforced by `config.ts`) |

The WebSocket relay is an equivalent substitute for TURN: media routes through the StreamNode server at zero extra cost, no external credentials, and no per-minute billing.

---

## 17. Documentation Index

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Deep dive: WebRTC flow, WebSocket URL derivation, channel lifecycle, module loading order, command routing, all key class descriptions |
| [docs/operations.md](docs/operations.md) | Setup, deployment, full Admin HTTP API reference, WebSocket endpoints, TURN config, Firebase setup, troubleshooting table |
| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Deep dive: WebRTC flow, transport fallback FSM, adaptive bitrate ladder, silence-aware suspend, ByteArrayPool, delta telemetry, WebSocket URL derivation, channel lifecycle, module loading order, command routing |
| [docs/operations.md](docs/operations.md) | Setup, deployment, full Admin HTTP API reference (incl. `set-transport`), WebSocket endpoints, new env vars (`STUN_SERVERS`, `METRICS_HISTORY_SIZE`, `TRANSPORT_FALLBACK_ENABLED`), TURN config, Firebase setup, troubleshooting table |
| [docs/extending.md](docs/extending.md) | Adding server modules, admin commands, telemetry fields (delta-aware), transport-aware features, adaptive bitrate hooks, ByteArrayPool usage, new `/control` message types, WebRTC video tracks |
| [docs/optimization-and-transport-plan.md](docs/optimization-and-transport-plan.md) | Full optimization & transport plan — **✅ all 38 items implemented**. Testing checklist updated with ✅ code-verified and 🧪 manual-test items |
| [server/MODULES.md](server/MODULES.md) | ServerModule API reference, ServerContext API, route priority rules, NPM scripts |
| [server/DEPLOY.md](server/DEPLOY.md) | Free cloud deployment on Render.com + custom subdomain + UptimeRobot keepalive |

---

## Admin API — Authentication

All `/admin/*` endpoints are **open** (no `ADMIN_SECRET` required). The `adminAuth` middleware unconditionally returns `true`. This is by design for local/private deployments. Do not expose port 4000 publicly without adding your own authentication layer.
