# StreamNode — Architecture (v5.2 + Optimization & Transport Plan)

## System Overview

StreamNode is a three-tier system:

```
┌───────────────────────────────────────────────────────────────────┐
│  Android App  (com.akdevelopers.streamnode)                         │
│                                                                    │
│  SetupActivity (4-step wizard)                                     │
│       ↓                                                            │
│  MainActivity (log console, start/stop)                            │
│       ↓                                                            │
│  StreamingService (FGS: microphone + mediaProjection + camera)     │
│       ↓                                                            │
│  ConnectionOrchestrator                                            │
│    ├─ WebRTCEngine      → /signal  (WebRTC P2P, default)          │
│    ├─ AudioControlClient → /control (always-on command channel)   │
│    ├─ AudioWebSocketClient → /stream (legacy Opus relay)          │
│    ├─ ScreenWebSocketClient → /screen (legacy screen share)       │
│    ├─ CameraOrchestrator → /camera-front|back (legacy cameras)    │
│    └─ FirebaseRemoteController → Firebase RTDB                    │
│                                                                    │
│  MicOrchestrator → AudioCaptureEngine → OpusEncoderWrapper       │
│  TelemetryReporter (every 60 s over /control)                     │
│  LocationReporter  (every 30 s over /control, opt-in)             │
└───────────────────────────────────────────────────────────────────┘
                          │  WebSocket / WebRTC
                          ▼
┌───────────────────────────────────────────────────────────────────┐
│  Node.js Server  (TypeScript, port 4000)                          │
│                                                                    │
│  server.ts → ServerContext (DI) → ModuleLoader (auto-discover)    │
│                                                                    │
│  Modules (alphabetical load order):                                │
│    AdminModule     → /admin/* HTTP                                 │
│    CameraModule    → WS /camera-front|back, /camera-*-watch       │
│    MetricsModule   → /metrics/* HTTP + SSE ring buffer            │
│    RecordingModule → /admin/:id/record/{start,stop,status}        │
│    ScreenModule    → WS /screen, /screen-watch                    │
│    SignalingModule → GET /ice-config, GET /webrtc-status, WS /signal │
│    StreamingModule → catch-all HTTP '*' + catch-all WS '*'        │
└───────────────────────────────────────────────────────────────────┘
                          │  HTTP + WebSocket
                          ▼
┌───────────────────────────────────────────────────────────────────┐
│  Browser Clients                                                   │
│    /              Dashboard (index.html) — device cards + map     │
│    /player?id=    WebRTC audio player + intercom UI               │
│    /pip?id=       Dual-camera PiP canvas                          │
│    /screen-view   Screen share viewer                              │
│    /camera-*-view Camera viewers                                   │
└───────────────────────────────────────────────────────────────────┘
```

---

## Android Module Graph

```
:app  (versionCode=12, versionName=2.0.0)
 ├── :feature:setup       SetupActivity — 4-step mandatory wizard
 ├── :feature:stream      MainActivity, MainViewModel, StreamingService
 ├── :data:streaming      ConnectionOrchestrator, WebRTCEngine,
 │                        AudioWebSocketClient, AudioControlClient,
 │                        SignalingClient, FirebaseRemoteController,
 │                        MicOrchestrator, AudioCaptureEngine,
 │                        InternalAudioCaptureEngine, TelemetryReporter,
 │                        LocationReporter, TorchController,
 │                        CameraOrchestrator, CommandProcessor,
 │                        InputInjector, StreamLockManager,
 │                        AppGraph, ServiceLocator
 ├── :domain:streaming    Interfaces: StreamOrchestrator, StreamIdentity,
 │                        AudioTransport, RemoteCommandSource,
 │                        StatusPublisher, MetricsPublisher,
 │                        StreamStatus, StreamLogger
 ├── :core:common         AppConstants, BaseViewModel, extensions,
 │                        coroutines utils
 ├── :core:deviceadmin    DeviceAdminCommander,
 │                        StreamNodeDeviceAdminReceiver
 ├── :core:observability  StreamNodeLogger, Analytics (Firebase),
 │                        CrashManager (Firebase Crashlytics)
 └── :core:platform       BootReceiver, WatchdogWorker,
                          NetworkChangeReceiver, PhoneCallMonitor,
                          SystemUtils
```

**Dependency rule:** `:feature:*` → `:data:streaming` → `:domain:streaming` → `:core:*`
No circular dependencies. `:app` is the composition root (compiles all modules).

---

## WebRTC Flow (Default Transport)

```
Phone                         Server (/signal WS)             Browser
  │─── WS /signal?role=phone ──>│                                │
  │<── {type:"welcome"} ─────────│                                │
  │                               │<── WS /signal?role=browser ──│
  │                               │──> {type:"phone-ready"} ────>│
  │<── {type:"phone-ready"} ──────│                                │
  │                               │                                │
  │── createOffer (ptime patched)─>│──> {offer, sdp} ─────────────>│
  │<── {answer, sdp} ─────────────│<── {answer, sdp} ──────────────│
  │── {ice-candidate} ────────────>│──> {ice-candidate} ───────────>│
  │<── {ice-candidate} ────────────│<── {ice-candidate} ─────────────│
  │                               │                                │
  │══════════════ SRTP/DTLS P2P audio (direct) ═══════════════════>│
  │                               │                                │
  │  [Intercom: browser speaks]   │                                │
  │<══════════════ remote audio track (SRTP) ═══════════════════════│
```

### Key WebRTC implementation details

- **Server role**: signaling relay only — no media bytes pass through it
- **Phone is always offerer**: `createAndSendOffer()` triggered by `phone-ready` message
- **SDP ptime patch**: `a=ptime:<currentFrameMs>` injected after every `m=audio` line so browsers know the Opus frame duration
- **`OfferToReceiveAudio: true`** in offer constraints — enables incoming intercom audio track
- **ICE restart**: on `IceConnectionState.FAILED`, `pc.restartIce()` + `scheduleRenegotiation()` (**500 ms** debounce — batches rapid multi-track adds into one offer/answer cycle)
- **Renegotiation guard**: `onRenegotiationNeeded` only sends offer if `signalingClient?.isOpen == true`
- **Track addition**: `addTrack()` fires `onRenegotiationNeeded` → `createAndSendOffer()` via `scheduleRenegotiation()`
- **Intercom activation**: `onTrack()` → `AudioManager.MODE_IN_COMMUNICATION` + `speakerphoneOn = true` → `onIntercomActive(true)`
- **Transport callbacks**: `onIceConnected` / `onIceFailed` invoke `ConnectionOrchestrator` fallback state machine

---

## WebSocket URL Construction

All URLs are derived from the single base `serverUrl` stored in `AppConstants.PREF_SERVER_URL`:

| Connection | Derived URL | Derivation method |
|---|---|---|
| `/control` | `wss://host/control?id=<uuid>&name=<device>` | Replace `/stream` path segment with `/control` |
| `/signal` | `wss://host/signal?id=<uuid>&role=phone` | Strip `/stream`, append `/signal` |
| `/ice-config` | `https://host/ice-config` | `wss://` → `https://`, strip `/stream` |
| `/stream` (legacy) | `wss://host/stream?id=<uuid>&name=<device>` | Used as-is |
| `/screen` (legacy) | `wss://host/screen?id=<uuid>&name=<device>` | Replace `/stream` with `/screen` |
| `/camera-front` (legacy) | `wss://host/camera-front?id=<uuid>&name=<device>` | Replace `/stream` with `/camera-front` |
| `/camera-back` (legacy) | `wss://host/camera-back?id=<uuid>&name=<device>` | Replace `/stream` with `/camera-back` |

`StreamIdentity.appendToUrl()` appends `?id=<uuid>&name=<displayName>`.

`ConnectionOrchestrator.normalizeStreamUrl()` ensures the URL always ends in `/stream` (never double-slash).

---

## Server Module Loading Order

`ModuleLoader` scans `src/modules/` alphabetically. **First-match-wins** route dispatch:

```
1. admin/AdminModule
   └─ HTTP: regex /^\/admin(?!\/[^/]+\/record\/)(\/|$)/
      (negative-lookahead excludes /admin/:id/record/* for RecordingModule)

2. camera/CameraModule
   └─ WS: /camera-front, /camera-back, /camera-front-watch, /camera-back-watch
   └─ HTTP: /camera-*-view pages

3. metrics/MetricsModule
   └─ HTTP: /metrics/:id/history  /metrics/:id/stream  /metrics/summary

4. reconnect/ReconnectModule
   └─ No routes — lifecycle hooks only:
      onStart() → writes serverStartedAt + serverOnline=true to Firebase,
                   sends "reconnect" Firebase command to all /users/ devices
      onStop()  → writes serverOnline=false to Firebase

5. recording/RecordingModule
   └─ HTTP: regex /^\/admin\/[^/]+\/record\/(start|stop|status)$/

6. screen/ScreenModule
   └─ WS: /screen, /screen-watch
   └─ HTTP: /screen-view page

7. signaling/SignalingModule
   └─ HTTP: /ice-config, /webrtc-status
   └─ WS: /signal

8. streaming/StreamingModule   ← catch-all, must be last alphabetically
   └─ HTTP: * (all unmatched)
   └─ WS: * (all unmatched: /stream, /control, /listen)
```

`ServerContext.httpDispatch()` and `wsDispatch()` iterate in registration order and call the **first matching handler**.

---

## Channel Lifecycle

```typescript
// Created on first connection by any phone socket
Channel {
  id: string               // UUID (from StreamIdentity)
  displayName: string      // Human-readable device name

  // Phone sockets (any one = phone "connected")
  controlWs: WebSocket | null    // /control — commands, telemetry, snapshot
  phoneWs: WebSocket | null      // /stream — legacy audio frames
  signalingPhone: WebSocket | null // /signal — WebRTC SDP/ICE

  // Browser sockets
  browsers: Set<WebSocket>           // /listen — audio relay receivers
  signalingBrowsers: Set<WebSocket>  // /signal browsers

  // State
  streamingActive: boolean
  callActive: boolean
  codecConfig: CodecConfig | null    // Opus params announced at connect
  recording: Recording              // ffmpeg proc + file state
  telemetry: Telemetry              // All telemetry fields (latest snapshot)
  snapshotResolve: ((buf) => void) | null  // Pending snapshot promise
  location: Location                // Latest GPS fix

  cleanupTimer: Timeout | null      // Grace period eviction timer

  stats: {
    framesRelayed, bytesRelayed,
    connectedAt, disconnectedAt, lastActivityMs
  }
}
```

**`isPhoneConnected(ch)`**: returns `true` if any of `controlWs || phoneWs || signalingPhone` is open.
This prevents premature eviction of WebRTC-only devices that skip `/stream`.

**Grace timer** (`CHANNEL_GRACE_MS`, default 5 min): when all phone sockets close, a timer is set. If no reconnection occurs within the grace period AND no browsers are listening, the channel is deleted from `channels.streams`. `cancelGrace()` cancels the timer on reconnect.

---

## Command Flow (Dual-Path with Dedup)

```
Dashboard HTTP button
  │
  ├──► POST /admin/:id/<action>
  │        AdminModule.dispatchAdminCommand()
  │          │
  │          ├─ ch.controlWs.readyState === OPEN?
  │          │    └──► controlWs.send({type:"cmd", commandId, action, url, source:"admin-websocket"})
  │          │              └──► AudioControlClient.onMessage()
  │          │                       └──► CommandProcessor.process()
  │          │
  │          └─ phone offline?
  │               └──► adminDb.ref(/users/:id/control).set({commandId, command, url, ...})
  │                        └──► FirebaseRemoteController.onCommandReceived()
  │                                 └──► CommandProcessor.process()
  │
  └──► POST /stream-control?id=&action=  (legacy HTTP fallback — same logic)


CommandProcessor.process():
  1. if commandId in executedIds → DROP (dedup)
  2. executedIds.add(commandId)
  3. Persist commandId to SharedPreferences
  4. if action.startsWith("admin_"):
       mainHandler.post { TorchController or DeviceAdminCommander.execute() }
  5. else:
       mainHandler.post { route to registered callback (onStart/onStop/etc.) }
```

`executedIds` is a `LinkedHashSet` bounded to `AppConstants.COMMAND_DEDUP_MAX_HISTORY` entries (LRU eviction of oldest). On app restart, the last `commandId` is restored from SharedPreferences to avoid re-executing the most recent command.

---

## Transport Fallback Architecture

`ConnectionOrchestrator` implements a hot-standby fallback state machine.

### TransportMode States

```kotlin
enum class TransportMode {
    WEBRTC_P2P,           // ICE connected — audio via SRTP/DTLS
    WEBSOCKET_RELAY,      // Audio through server relay
    WEBRTC_NEGOTIATING,   // ICE in progress
    FALLBACK_TRIGGERED,   // ICE failed — WS activating, WebRTC retrying
}
```

### Fallback Flow

```
App start → standby WS pre-connected (audioSuspended=true)
                │
     WebRTCEngine.onIceConnected
                │
       transitionTo(WEBRTC_P2P)
       standbyWsClient.suspendAudio()
                │
     WebRTCEngine.onIceFailed
                │
       transitionTo(FALLBACK_TRIGGERED)
       standbyWsClient.activateAudio()   ← <50 ms switchover, zero audio gap
       wsClient = standbyWsClient
       transitionTo(WEBSOCKET_RELAY)
                │
     pc.restartIce() succeeds later
                │
       transitionTo(WEBRTC_P2P)
       standbyWsClient.suspendAudio()
```

Each transition calls `sendTransportStatus()` → `/control` → server stores in `ch.transportMode` → broadcast to dashboard browsers as `{type:"transportStatus", mode, rtt, iceState}`.

### Server-Side Transport Fields

```typescript
Channel {
  transportMode:  string | null   // "webrtc_p2p" | "websocket_relay" | "negotiating" | "fallback"
  transportRttMs: number | null   // active transport RTT in ms
  iceState:       string | null   // "connected" | "failed" | "checking"
  silenceActive:  boolean         // true when AudioRecord soft-suspended after 5 s silence
}
```

---

## Battery, CPU & Data Optimizations

### AudioCaptureEngine — Silence-Aware Soft-Suspend

After `~5 s` of consecutive VOX-gated silence, `AudioRecord.stop()` releases the DSP pipeline (~10 mA saving). A 50 ms poll loop detects RMS spikes and calls `startRecording()` instantly. No object re-allocation.

```
captureLoop running
    │
  frame RMS < threshold?
    │ YES → silenceSuspendFrames++
    │       if >= threshold (5 s) → audioRecord.stop()
    │                              onSilenceStateChanged(true) → server notified
    │
    │ [suspended: poll every 50 ms]
    │       probe frame RMS >= threshold?
    │       YES → audioRecord.startRecording()
    │             onSilenceStateChanged(false) → server notified
    │             resume normal loop
```

### ByteArrayPool — Zero Per-Frame Allocations

```kotlin
object ByteArrayPool {
    fun acquire(size: Int): ByteArray  // reuses pooled buffer or allocates
    fun release(buf: ByteArray)        // returns to pool (max 8 entries)
}
```

`AudioCaptureEngine` acquires one buffer per capture-loop lifetime and releases it in `finally {}`. Eliminates GC pressure from ~50 allocs/second at 48 kHz/20 ms.

### Adaptive Telemetry Interval

`TelemetryReporter.setInterval()` is called by `StreamingService.screenStateReceiver`:

| Condition | Interval |
|---|---|
| Screen ON | 60 s |
| Screen OFF + streaming | 120 s |
| Screen OFF + idle | 300 s |

### Delta Telemetry

Only fields that changed beyond their noise threshold are included in each tick. A full resync fires every 5 minutes. The server's `handleControl` merges delta fields onto `ch.telemetry` rather than replacing it.

### Adaptive Bitrate Ladder

`AudioControlClient` measures pong-based RTT every ~20 s. `ConnectionOrchestrator.onRttUpdate` forwards it to `MicOrchestrator.autoQualityFromRtt()`:

| RTT | Action |
|---|---|
| < 150 ms | Keep `HIGH_QUALITY` |
| 150–400 ms | Step down to `MEDIUM` (immediate) |
| > 400 ms | Step down to `LOW` (immediate) |
| Improves | Step up after **30 s** hysteresis |

---

```
AudioRecord (PCM 16-bit)
  │
  ↓ (per frame loop in AudioCaptureEngine coroutine)
  ├─ VOX gate: if RMS < silenceGateRms → DROP frame
  │
  ↓
OpusEncoderWrapper.encode(pcmFrame)  → Opus binary packet
  │
  ↓
onFrameReady callback
  │
  ├─► ConnectionOrchestrator.sendFrame()
  │       ├─ WebRTC path: AudioTrack (WebRTC ADM handles encoding internally)
  │       └─ Legacy WS: AudioWebSocketClient.sendFrame() → binary WS message
  │
  └─► Recording frameHook (if active):
          ffmpegProc.stdin.write(frame)  → OGG file on disk
```

**Quality config** (`AudioQualityConfig`): immutable data class holding `sampleRate`, `opusBitrate`, `frameMs`, `vbrEnabled`, `opusComplexity`, `enableAgc`, `enableNs`, `enableAec`, `silenceGateRms`, `preset`. `MicOrchestrator` restarts `AudioCaptureEngine` on any config change (non-call mode).

---

## Telemetry Data Flow

```
TelemetryReporter (adaptive interval: 60/120/300 s, main thread Handler)
  │
  ├─ reads: BatteryManager, TelephonyManager, Runtime, ConnectivityManager,
  │          PowerManager, /sys/class/thermal/thermal_zone*
  ├─ calls: getVoxMetrics() → MicOrchestrator.voxMetrics()
  ├─ calls: getStreamMetrics() → MicOrchestrator metrics window
  ├─ DELTA: builds diff vs last snapshot; only changed fields included
  └─ FULL RESYNC: every 5 min regardless of changes
  │
  ↓
  AudioControlClient.send(jsonString)  [with delta:true if partial]
  │
  ↓
  Server: routes/websocket.ts handleControl()
  │
  ├─ p.delta? → merge fields onto ch.telemetry  (field-level merge)
  │           : replace ch.telemetry entirely
  ├─ MetricsModule.recordTelemetry(deviceId, telemetry) ← ring buffer (60 entries)
  └─ channels.broadcast(ch, {type:"telemetry", ...}) ← /listen browsers
         │
         └─► Dashboard: buildTelemetryStrip() + alert checks
         └─► MetricsModule SSE: push to /metrics/:id/stream clients
```

---

## Key Android Classes (Full Reference)

| Class | Module | Key responsibility |
|---|---|---|
| `StreamingService` | :feature:stream | FGS lifecycle; wires all collaborators via AppGraph; intent routing |
| `ConnectionOrchestrator` | :data:streaming | Owns all network connections; routes between WebRTC and legacy WS |
| `WebRTCEngine` | :data:streaming | `RTCPeerConnection` lifecycle; tracks; ICE; offer/answer; intercom |
| `SignalingClient` | :data:streaming | WebSocket to `/signal` with exponential backoff reconnect |
| `AudioControlClient` | :data:streaming | WebSocket to `/control`; sends/receives JSON; handles snapshot binary response |
| `AudioWebSocketClient` | :data:streaming | WebSocket to `/stream`; binary Opus frame sender (legacy path) |
| `MicOrchestrator` | :data:streaming | AudioRecord session lifecycle; quality/sample rate/VBR/VOX switching; backoff restart; call mode |
| `AudioCaptureEngine` | :data:streaming | Coroutine-based AudioRecord read loop; Opus encoding; VOX gate |
| `InternalAudioCaptureEngine` | :data:streaming | API 29+ AudioPlaybackCaptureConfiguration; captures media/game output audio |
| `OpusEncoderWrapper` | :data:streaming | JNI wrapper around libopus (from `opus.aar`) |
| `TelemetryReporter` | :data:streaming | Samples device metrics; sends over `/control` every 60 s |
| `LocationReporter` | :data:streaming | FusedLocationProvider; sends GPS over `/control` every 30 s |
| `TorchController` | :data:streaming | `CameraManager.setTorchMode()` |
| `CameraOrchestrator` | :data:streaming | Camera2 snapshot capture; legacy H.264 WS streaming |
| `CommandProcessor` | :data:streaming | Single deduplicating entry point for all commands from any source |
| `InputInjector` | :data:streaming | Accessibility-based remote touch/key injection |
| `FirebaseRemoteController` | :data:streaming | RTDB listener; publishes status/metrics; offline command delivery |
| `StreamIdentity` | :domain:streaming | Persistent UUID + display name; `appendToUrl()` |
| `AppGraph` | :data:streaming | Manual DI composition root — creates and wires all collaborators |
| `ServiceLocator` | :data:streaming | Process-wide singleton access to `AppGraph` |
| `DeviceAdminCommander` | :core:deviceadmin | All `DevicePolicyManager` operations |
| `StreamNodeDeviceAdminReceiver` | :core:deviceadmin | `DeviceAdminReceiver` for Device Admin/Owner |
| `BootReceiver` | :core:platform | Handles boot intents; starts `StreamingService` if `PREF_AUTO_START_ON_BOOT` |
| `PhoneCallMonitor` | :core:platform | `TelephonyManager` call state listener; triggers call mode |
| `NetworkChangeReceiver` | :core:platform | `ConnectivityManager` changes → reconnect |

---

## Known Bugs Fixed in This Codebase

| # | File | Bug | Fix applied |
|---|---|---|---|
| 1 | `feature:stream/ui/MainActivity.kt` | `allPermsGranted()` did not check `CAMERA` — app entered main UI with camera permission revoked | Added `hasPerm(CAMERA)` check |
| 2 | `server/src/modules/camera/CameraModule.ts` | `__dirname`-relative HTML path broke in compiled (`dist/`) mode | Changed to `process.cwd() + "public/camera.html"` |
| 3 | `server/src/modules/screen/ScreenModule.ts` | Same `__dirname` path issue for `screen.html` | Changed to `process.cwd() + "public/screen.html"` |
| 4 | `README.md` | Referenced non-existent `ADMIN_SECRET` in Quick Start | Removed all `ADMIN_SECRET` references |
| 5 | TypeScript + Kotlin compile | Both `tsc --noEmit` and `assembleDebug` pass zero errors | Verified — no regressions |

---

## Always-On Connection Architecture

Six gaps in the reconnect system were identified and fixed. Below is the complete picture of how the app and server stay connected at all times.

### Gap Fixes Summary

| Gap | Problem | Fix |
|---|---|---|
| **Gap 1** — `PREF_AUTO_RESTART` never set until first stream | Fresh device reboots after wizard completion but before first `start` command — service never auto-starts | `SetupActivity.launchMain()` now sets `PREF_AUTO_RESTART=true` + `PREF_AUTO_START_ON_BOOT=true` at wizard completion |
| **Gap 2** — No server-restart notification | Server comes back after 24 h outage; Android waits up to 5 min in Phase-2 backoff | `ReconnectModule.onStart()` writes `serverStartedAt` to Firebase; `FirebaseRemoteController.startServerStartedWatcher()` detects change and calls `reconnectNow()` in <3 s |
| **Gap 3** — Firebase offline persistence disabled | Pending RTDB writes lost on process kill | `StreamNodeApp.onCreate()` calls `FirebaseDatabase.setPersistenceEnabled(true)` before any `getReference()` |
| **Gap 4** — Ghost "online" on crash/kill | `stop()` never runs on OOM kill → Firebase stuck at `online: true` | `FirebaseRemoteController.start()` registers `statusRef.onDisconnectSetValue(false)` — Firebase servers execute it automatically ~60-90 s after client disappears |
| **Gap 5** — `SCHEDULE_EXACT_ALARM` never requested | Android 12+ watchdog alarm deferred up to 9 min in Doze without exact-alarm grant | `SetupActivity` shows dialog on API 31+ at wizard end; manifest declares `SCHEDULE_EXACT_ALARM` |
| **Gap 6** — No server wake-blast on startup | Server had no way to proactively wake devices after restart | `ReconnectModule.onStart()` reads `/users/` from Firebase Admin and sends `reconnect` command to every known device |

### New Firebase RTDB Paths

```
/streamnode_config/serverUrl_v2       (existing — server URL for v2 APKs)
/streamnode_config/serverStartedAt    number  — unix ms, written by server on every startup
/streamnode_config/serverOnline       boolean — true on startup, false on graceful SIGINT
```

### Reconnect Layers (all active simultaneously)

```
Layer 1 — NetworkChangeReceiver
  ConnectivityManager.NetworkCallback → onAvailable() → reconnectNow()
  Resets backoff to 0. Fires in <1 s when internet returns.
  3 s startup grace + 3 s cooldown prevent storms.

Layer 2 — Firebase serverStartedAt watcher  [NEW — Gap 2]
  FirebaseRemoteController.startServerStartedWatcher()
  Detects server restart in <3 s via Firebase push.
  Calls reconnectNow() immediately, bypassing Phase-2 backoff.

Layer 3 — Two-phase exponential backoff  (AudioControlClient / SignalingClient)
  Phase 1 (attempts 1-10): 1→2→4→…→30 s cap
  Phase 2 (attempts 11+):  cap raised to 5 min (battery conservation)
  Reset to Phase 1 by Layer 1 or Layer 2.

Layer 4 — WatchdogWorker (dual sub-layers)
  4a. WorkManager + NETWORK_CONNECTED constraint (15 min)
  4b. AlarmManager setExactAndAllowWhileIdle (15 min, no constraint)
  4c. WorkManager no-network job (30 min)  [NEW — Gap P2]
  checkAndRestart(): dead process → restart; alive+disconnected → triggerReconnect()

Layer 5 — Firebase offline command delivery
  Server writes {command:"reconnect"} to /users/{id}/control when phone WS is closed.
  App delivers command on next Firebase connection.
  Wake blast in ReconnectModule.onStart() covers all known devices.  [NEW — Gap 6]

Layer 6 — BootReceiver
  Restarts service on BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED.
  Now triggered from first wizard completion (not only after first stream).  [NEW — Gap 1]
```
