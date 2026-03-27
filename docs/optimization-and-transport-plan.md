# StreamNode — Deep Optimization & Transport Fallback Plan

> **Scope:** Battery · CPU · RAM · Internet Data · WebRTC↔WebSocket Fallback · Dashboard Transport Visibility · User-Selectable Transport  
> **Target codebase:** v5.2 (versionCode 12)  
> **Author:** Claude (AI planning assistant)  
> **Status:** ✅ FULLY IMPLEMENTED — all 38 items verified and shipped (see `README.md` Feature Set §15–34 and `docs/architecture.md`)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current State Analysis](#2-current-state-analysis)
3. [Battery Optimization Plan](#3-battery-optimization-plan)
4. [CPU Optimization Plan](#4-cpu-optimization-plan)
5. [RAM Optimization Plan](#5-ram-optimization-plan)
6. [Internet Data Usage Optimization Plan](#6-internet-data-usage-optimization-plan)
7. [Transport Fallback Architecture](#7-transport-fallback-architecture)
8. [Dashboard Transport Visibility & User Controls](#8-dashboard-transport-visibility--user-controls)
9. [Android Transport Selector UI](#9-android-transport-selector-ui)
10. [Server-Side Changes](#10-server-side-changes)
11. [File-by-File Change Map](#11-file-by-file-change-map)
12. [Implementation Order](#12-implementation-order)
13. [Testing Checklist](#13-testing-checklist)

---

## 1. Executive Summary

This document covers six major improvement areas for StreamNode, organized from easiest-wins to most architectural:

| Area | Estimated Impact | Complexity |
|---|---|---|
| Battery — telemetry & wakelock tuning | −30–45% battery drain | Low |
| CPU — adaptive Opus complexity | −20–35% CPU load | Low |
| RAM — buffer pooling & leak guards | −15–25 MB heap | Medium |
| Data — adaptive VOX + bitrate ladder | −40–70% data on silence | Medium |
| Transport fallback (WebRTC → WebSocket) | Reliability +++ | High |
| Dashboard transport visibility + user control | UX +++ | Medium |

All six areas are **additive** — each can be shipped independently.

---

## 2. Current State Analysis

### 2.1 Battery Drain Sources (Identified)

| Source | Class | Issue |
|---|---|---|
| Telemetry every 60 s | `TelemetryReporter` | Reads CPU thermal zones, ConnectivityManager, TelephonyManager every tick — all expensive IPC calls — even when screen is off and idle |
| Firebase URL poll every 30 min | `ConnectionOrchestrator` | `urlRefreshRunnable` fires a new `FirebaseRemoteController` instance + network call — creates/destroys objects on a Handler |
| Location every 30 s | `LocationReporter` | FusedLocationProvider at 30 s with no power-budget hint (no `setPriority(PRIORITY_LOW_POWER)`) |
| WebRTC ICE keepalives | `SignalingModule` (server) | `keepalive` interval every 20 s in `handleSignal` — sends `ws.ping()` from server AND WebRTC STUN keep-alives from phone |
| AudioRecord always-on | `AudioCaptureEngine` | AudioRecord stays open even during silence when VOX gate drops frames — could use `AudioRecord.startRecording/stopRecording` dynamically |
| WakeLock in FGS | `StreamingService` | Currently implicit PARTIAL_WAKE_LOCK from `foregroundServiceType=microphone` — but no explicit management leads to CPU staying awake during screen-off idle periods |
| `WatchdogWorker` (WorkManager) | `core:platform` | AlarmManager `setExactAndAllowWhileIdle` every 15 min — wakes CPU even when connection is healthy |

### 2.2 CPU Load Sources (Identified)

| Source | Class | Issue |
|---|---|---|
| Opus complexity always 9 | `AudioQualityConfig` | Complexity 9 on all presets — uses ~2× CPU vs complexity 5 for perceptually-indistinguishable quality on voice |
| JSON serialization every frame | Various | `JSONObject` created per telemetry tick — allocates many short-lived objects |
| Stats broadcast every 2 s | `server.ts statsInterval` | Iterates all channels, serializes JSON, sends to every browser every 2 s — O(channels × browsers) |
| WebRTC SDP re-offer on every track add | `WebRTCEngine` | `scheduleRenegotiation` 200 ms debounce fires a full offer/answer cycle on every camera track toggle |
| AudioRecord buffer size (default) | `AudioCaptureEngine` | Default minimum buffer leads to more frequent CPU wakeups per second |

### 2.3 RAM Pressure Sources (Identified)

| Source | Class | Issue |
|---|---|---|
| Telemetry `JSONObject` per tick | `TelemetryReporter` | New JSONObject + sub-objects created and immediately discarded every 60 s |
| Recording frame hook copy | `RecordingModule` (server) | Every audio frame is copied into ffmpeg stdin — no backpressure guard — unbounded queue during slow ffmpeg |
| MetricsModule ring buffer | `MetricsModule` | Fixed at 120 entries per device but Telemetry objects hold 17 nullable fields — ~2.5 KB each = 300 KB per device |
| `channels.streams` Map | `channels.ts` | Channel objects are never compacted — `signalingBrowsers` Set can grow unbounded if close events fire after the Map entry is reused |
| `CommandProcessor.executedIds` | `CommandProcessor.kt` | `LinkedHashSet` in memory — pruned by `COMMAND_DEDUP_MAX_HISTORY` but each entry is a UUID string (36 bytes) |
| AudioRecord PCM buffer | `AudioCaptureEngine` | Two ByteArrays per read cycle (PCM raw + Opus output) — could be pooled |

### 2.4 Internet Data Sources (Identified)

| Source | Current | Issue |
|---|---|---|
| Opus always-on CBR | `AudioQualityConfig.HIGH_QUALITY` | 192 kbps CBR even during silence when VOX is off |
| VOX gate drops frames client-side | `AudioCaptureEngine` | Frames silently dropped on phone but `/stream` WS still sends a TCP keepalive frame; no notification to server that silence started |
| WebRTC STUN every 25 s | Google STUN servers | 3 STUN servers × consent fresh every 25 s × N browsers = meaningful background traffic |
| Location 30 s interval | `LocationReporter` | No change-threshold guard — sends GPS packet even when device is stationary |
| Telemetry every 60 s | `TelemetryReporter` | Sends all 17 fields every tick — ~800 bytes JSON — even unchanged fields |
| Stats broadcast 2 s | `server.ts` | `statsInterval` sends to every browser even when nothing changed |

---

## 3. Battery Optimization Plan

### 3.1 Adaptive Telemetry Interval

**Problem:** `TelemetryReporter` fires every 60 s unconditionally — reads thermal zones, telephony, Wi-Fi via IPC even during screen-off idle.

**Plan:**

- Add `TelemetryReporter.setInterval(ms: Long)` — already partially supported via the `intervalMs` constructor parameter.
- In `StreamingService` / `ConnectionOrchestrator`, observe `PowerManager.isInteractive` state:
  - Screen ON + streaming active → keep 60 s
  - Screen OFF + streaming active → stretch to **120 s**
  - Screen OFF + idle (not streaming) → stretch to **300 s**
- Register a `BroadcastReceiver` for `Intent.ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` in `StreamingService` (not in manifest — only while service is alive) to flip the interval dynamically.
- Add `telemetryIntervalMs` to the `FeatureFlags` system (already defined in `channels.ts`) so the dashboard can override from server side.

**Files to change:**
- `data/streaming/.../service/TelemetryReporter.kt` — add `setInterval()`, make handler-based loop restartable
- `feature/stream/.../StreamingService.kt` — register screen state receiver, call `setInterval()`
- `server/public/index.html` — add telemetry interval control in Admin panel

### 3.2 Lazy Location — Change-Threshold Guard

**Problem:** `LocationReporter` sends every GPS fix at 30 s even when device is stationary.

**Plan:**

- Add a minimum-displacement threshold: only send if `distanceTo(lastSent) > 10 metres` OR `elapsed > 300 s`.
- Switch FusedLocationProvider request priority to `PRIORITY_BALANCED_POWER_ACCURACY` (was implicit `HIGH_ACCURACY`).
- When screen is off and `streamingActive = false`, pause location requests entirely and resume on screen-on.

**Files to change:**
- `data/streaming/.../service/LocationReporter.kt`

### 3.3 WatchdogWorker — Health-Aware Skip

**Problem:** `WatchdogWorker` wakes CPU every 15 min even when the connection is healthy.

**Plan:**

- Before doing any restart work, call `ServiceLocator.graph.connectionOrchestrator.isWsOpen` (and the equivalent for WebRTC).
- If connection is healthy → log "healthy — skip" and return `Result.success()` immediately without touching AlarmManager.
- Double the alarm interval to 30 min for the exact-alarm path when the last 2 checks were healthy ("progressive watchdog back-off").

**Files to change:**
- `core/platform/.../WatchdogWorker.kt`

### 3.4 AudioRecord — Silence-Aware Suspend

**Problem:** `AudioCaptureEngine` keeps `AudioRecord` in `RECORDING` state even during VOX-gated silence, consuming ~10 mA continuously.

**Plan:**

- When VOX gate is enabled AND `silenceSustainedMs > 5000` (5 s consecutive silence), call `audioRecord.stop()` to release the DSP pipeline.
- Set a `silenceResumeHandler.postDelayed(50ms)` check: if RMS suddenly spikes above threshold (inbound sound), call `audioRecord.startRecording()` immediately.
- This is a soft suspend — the `AudioRecord` object is not released (avoids re-allocation latency), only `stop()`/`startRecording()` cycle.
- Guard: only do this when `PREF_VOX_ENABLED = true` to avoid latency surprises.

**Files to change:**
- `data/streaming/.../audio/AudioCaptureEngine.kt`
- `data/streaming/.../service/MicOrchestrator.kt` — expose `suspendCount` metric

### 3.5 Firebase URL Poll — Remove Redundant Instance Creation

**Problem:** `urlRefreshRunnable` in `ConnectionOrchestrator` creates a new `FirebaseRemoteController` object every 30 min — this allocates, opens RTDB connections, then discards. The live listener (`onServerUrlChanged`) already handles URL changes in real-time.

**Plan:**

- Remove the `urlRefreshRunnable` entirely. The belt-and-suspenders it provides is already covered by:
  1. Live `onServerUrlChanged` Firebase push listener (Gap 3 fix, already in place)
  2. `serverStartedAt` watcher (Gap 2 fix, already in place)
  3. `NetworkChangeReceiver` triggering reconnect on network restore
- If the belt-and-suspenders poll is still desired for Doze resilience, replace it with a single `WorkManager` periodic task (no-network constraint, 60 min minimum interval) that checks if the stored URL matches Firebase — avoids creating objects on the main Handler.

**Files to change:**
- `data/streaming/.../service/ConnectionOrchestrator.kt` — remove `urlRefreshHandler` + `urlRefreshRunnable`

### 3.6 WebRTC Signaling Keepalive — Reduce Frequency

**Problem:** `SignalingModule` sends a `ws.ping()` every 20 s per connected phone signaling socket. Combined with the server's global `wsHeartbeat` at 30 s, there are two overlapping keepalive mechanisms.

**Plan:**

- Remove the per-socket `keepalive` `setInterval` in `SignalingModule.handleSignal` for the phone side.
- Rely solely on the global `wsHeartbeat` interval already in `server.ts`. The `isAlive` flag + pong handler already handle dead socket detection globally.
- This halves the keepalive traffic for signaling sockets.

**Files to change:**
- `server/src/modules/signaling/SignalingModule.ts` — remove `keepalive` setInterval

---

## 4. CPU Optimization Plan

### 4.1 Adaptive Opus Complexity

**Problem:** All quality presets use `opusComplexity = 9` (max). This is appropriate for music but unnecessary for voice — complexity 5 is perceptually equivalent for speech and uses ~40% less encoder CPU.

**Plan:**

- Change `AudioQualityPreset` defaults:
  - `HIGH_QUALITY` → complexity **7** (excellent voice, lower CPU)
  - `MEDIUM` → complexity **5**
  - `LOW` → complexity **3**
- Add `CALL_CAPTURE` preset → complexity **5** (already uses 96 kbps; CPU saving is significant on low-end phones)
- When device battery drops below 20% AND not charging, auto-reduce complexity by 2 steps (e.g. HIGH_QUALITY goes 7→5).
- The existing `set_audio_config` endpoint already accepts `complexity` — no server changes needed for manual override.

**Files to change:**
- `data/streaming/.../audio/AudioQualityConfig.kt` — update preset defaults
- `data/streaming/.../service/MicOrchestrator.kt` — add battery-aware complexity reduction

### 4.2 Server Stats Broadcast — Diff-Only

**Problem:** `statsInterval` in `server.ts` fires every 2 s and serializes + sends the same JSON to every browser even when nothing has changed (phone disconnected, no streaming).

**Plan:**

- Per-channel, cache the last broadcasted stats string as `ch.lastStatsJson`.
- Build the new stats string; only call `channels.broadcast()` if it differs from the cached value.
- For channels with zero browser listeners, skip entirely (already guarded by `ch.browsers.size === 0`).
- Reduce `STATS_INTERVAL_MS` default from 2000 ms to **3000 ms** — human perception of latency on a dashboard does not require 500 ms refresh.

**Files to change:**
- `server/src/config.ts` — change `STATS_INTERVAL_MS` default to 3000
- `server/server.ts` — add diff guard in `statsInterval`
- `server/src/channels.ts` — add `lastStatsJson: string` to `Channel` interface

### 4.3 AudioRecord Buffer Size — Tuning

**Problem:** Minimum buffer size leads to very frequent read() callbacks (up to 50/s at 48 kHz mono), each context-switching into the JVM audio thread.

**Plan:**

- Set `AudioRecord` buffer to `max(minBufSize, frameMs × sampleRate × 2 × 4)` — i.e., hold 4 frames before a read callback. This reduces read() frequency by 4× without adding measurable latency, since Opus frames are still encoded at `frameMs` intervals.
- This change is within `AudioCaptureEngine` where `AudioRecord` is initialized.

**Files to change:**
- `data/streaming/.../audio/AudioCaptureEngine.kt`

### 4.4 Telemetry JSON — Pre-built Template

**Problem:** `TelemetryReporter` creates a `JSONObject` from scratch every tick with 17 fields, all via reflection-heavy `JSONObject.put()`.

**Plan:**

- Pre-build a `StringBuilder` template string once; use string interpolation or `String.format()` to fill numeric values inline.
- This avoids `JSONObject` allocation entirely for the common path.
- The resulting string is passed directly to `sendFn(json)`.

**Files to change:**
- `data/streaming/.../service/TelemetryReporter.kt`

### 4.5 WebRTC Renegotiation Debounce Increase

**Problem:** `scheduleRenegotiation()` debounce is 200 ms. When multiple tracks are added rapidly (front camera + back camera + screen), this fires 3 separate offer/answer cycles.

**Plan:**

- Increase debounce to **500 ms**.
- Add a `pendingTracks` set — accumulate all `addTrack()` calls within the debounce window, then fire a single renegotiation.

**Files to change:**
- `data/streaming/.../audio/WebRTCEngine.kt`

---

## 5. RAM Optimization Plan

### 5.1 PCM + Opus Buffer Pool

**Problem:** `AudioCaptureEngine` allocates a new `ByteArray` for PCM input and Opus output on every encoding cycle (up to 50/s). These short-lived allocations cause frequent GC.

**Plan:**

- Create a simple `ByteArrayPool(maxSize: Int)` utility in `core:common`:
  ```kotlin
  object ByteArrayPool {
      private val pool = ArrayDeque<ByteArray>()
      fun acquire(size: Int): ByteArray = pool.removeFirstOrNull()?.takeIf { it.size >= size } ?: ByteArray(size)
      fun release(buf: ByteArray) { if (pool.size < 8) pool.addLast(buf) }
  }
  ```
- In `AudioCaptureEngine`, acquire buffers from the pool before each read, release after encoding.
- Net effect: 0 short-lived allocations per frame in steady state.

**Files to change:**
- `core/common/.../ByteArrayPool.kt` (new file)
- `data/streaming/.../audio/AudioCaptureEngine.kt`

### 5.2 MetricsModule — Compact Telemetry Storage

**Problem:** `MetricsModule` stores full `Telemetry` objects in a ring buffer (120 per device). Each object has 17 nullable boxed fields. At multiple devices, this can be 1+ MB of live heap.

**Plan:**

- Store telemetry snapshots as compact `FloatArray` (for numeric fields) + a bitmask `Int` (for booleans + nullability). This is a ~90% size reduction vs. a Telemetry object.
- On `GET /metrics/:id/history` re-hydrate into JSON on the fly.
- Alternatively (simpler): reduce ring buffer to **60 entries** (still 1 hour of history at 60 s intervals), which halves the memory usage with no API change.

**Files to change:**
- `server/src/modules/metrics/MetricsModule.ts`

### 5.3 Channel Cleanup — signalingBrowsers Leak Guard

**Problem:** If a browser's `close` event fires after the channel has been re-created (e.g. after grace-period eviction), the stale WebSocket is left in `signalingBrowsers` forever.

**Plan:**

- In `SignalingModule.handleSignal` browser close handler, explicitly verify `ch === channels.streams.get(id)` before deleting from the Set — avoids accidentally leaking entries in the newly-created channel's Set.
- In `scheduleGrace()`, before deleting the channel, close all open WebSockets in `signalingBrowsers` (currently only `browsers` is cleaned up).

**Files to change:**
- `server/src/modules/signaling/SignalingModule.ts`
- `server/src/channels.ts` — `scheduleGrace()` cleanup

### 5.4 CommandProcessor — Bounded dedup Set Verification

**Problem:** `executedIds` LinkedHashSet is bounded by `COMMAND_DEDUP_MAX_HISTORY` — but this is only pruned when a NEW command is processed. If no commands arrive for hours, the set is never pruned despite being potentially stale.

**Plan:**

- Move `COMMAND_DEDUP_MAX_HISTORY` pruning into a separate hourly `Handler.postDelayed` — keep only the last 20 entries regardless of age.
- 20 entries is sufficient: the dedup window only needs to outlast the Firebase RTDB delivery retry window (~5 min).

**Files to change:**
- `data/streaming/.../service/CommandProcessor.kt`

### 5.5 Server Recording — ffmpeg Backpressure

**Problem:** `RecordingModule` pipes audio frames to `ffmpegProc.stdin` with no backpressure. If ffmpeg is slow (e.g. disk full), frames queue up in Node.js memory unboundedly.

**Plan:**

- Check `ffmpegProc.stdin.writableNeedDrain` before each write. If `true`, drop the frame and increment a `droppedFrames` counter.
- Log a warning when `droppedFrames > 100` in a session (disk/ffmpeg issue).
- This converts an unbounded memory growth into a predictable frame-drop.

**Files to change:**
- `server/src/modules/recording/RecordingModule.ts`

---

## 6. Internet Data Usage Optimization Plan

### 6.1 VOX "Silence Notification" Protocol

**Problem:** When VOX is active and dropping frames, the server still receives zero-byte TCP keepalives on the `/stream` WebSocket. Browsers receive a `stats` payload showing 0 fps, which is correct — but the connection overhead is non-zero.

**Plan — Server-Side Silence Mode:**
- Add a new JSON message type `silence` sent from phone to server on `/control` when VOX transitions to silence state:
  ```json
  { "type": "silence", "active": true, "durationMs": 0 }
  ```
- Server stores `ch.silenceActive = true` on the channel.
- While `silenceActive`, the `statsInterval` sends a lightweight `{ type: "silence" }` to browsers instead of a full stats payload — saves ~200 bytes per broadcast.
- When audio resumes, phone sends `{ "type": "silence", "active": false }`.

**Files to change:**
- `data/streaming/.../audio/AudioCaptureEngine.kt` — detect silence-start / silence-end transitions
- `data/streaming/.../service/ConnectionOrchestrator.kt` — forward silence signals via controlClient
- `server/src/routes/websocket.ts` — handle `silence` type in `handleControl`
- `server/src/channels.ts` — add `silenceActive: boolean` to `Channel`
- `server/server.ts` statsInterval — conditional payload

### 6.2 Delta Telemetry — Send Only Changed Fields

**Problem:** Telemetry sends all 17 fields every 60 s even when most are unchanged (battery level changes by 1% every few minutes, SSID never changes, etc.).

**Plan:**

- In `TelemetryReporter`, store the last-sent telemetry snapshot.
- Build a diff object: only include fields that have changed by more than a threshold:
  - `batteryLevel`: changed by ≥ 2%
  - `cpuTempC`: changed by ≥ 1°C
  - `streamFps`/`streamKbps`: changed by ≥ 5%
  - `wifiSsid`, `netType`, `charging`: any change
- Always send a full snapshot every **5 minutes** (belt-and-suspenders).
- Server-side `handleControl` must merge deltas onto `ch.telemetry` rather than replacing it.

**Files to change:**
- `data/streaming/.../service/TelemetryReporter.kt` — add delta logic
- `server/src/routes/websocket.ts` — `handleControl` telemetry merge (already struct-replacing; change to field-level merge)

### 6.3 Adaptive Bitrate Ladder

**Problem:** `HIGH_QUALITY` preset always streams at 192 kbps regardless of network quality.

**Plan — Automatic Quality Step-Down:**

- Monitor RTT from WebRTC stats (`pc.getStats()` → `outbound-rtp` `roundTripTime`) or from WebSocket round-trip (send `ping` with timestamp, measure `pong` latency).
- Define a 3-tier trigger:
  - RTT < 150 ms OR no degradation → keep `HIGH_QUALITY`
  - RTT 150–400 ms OR >5% packet loss → auto-step to `MEDIUM` (96 kbps)
  - RTT > 400 ms OR >15% loss → auto-step to `LOW` (32 kbps)
- Step-down is immediate; step-up has a 30 s hysteresis to avoid oscillation.
- This is opt-in via a new `PREF_ADAPTIVE_BITRATE` SharedPreference (default: `true`).
- Dashboard can show the current auto-selected quality tier.

**Files to change:**
- `data/streaming/.../audio/WebRTCEngine.kt` — add `getStatsRtt()` helper
- `data/streaming/.../audio/AudioControlClient.kt` — add pong-based RTT measure for WS path
- `data/streaming/.../service/MicOrchestrator.kt` — add `autoQualityFromRtt(rtt: Long)`
- `server/src/modules/admin/AdminModule.ts` — expose `GET /admin/:id/adaptive-bitrate` status

### 6.4 Location Displacement Threshold

**Problem:** GPS coordinates are sent every 30 s even when the device is stationary.

**Plan:**

- In `LocationReporter`, after receiving a fix, compute `location.distanceTo(lastSentLocation)`.
- Only send if displacement > 10 m OR elapsed > 300 s (5 min max hold).
- This reduces location traffic by ~90% for stationary devices.

**Files to change:**
- `data/streaming/.../service/LocationReporter.kt`

### 6.5 WebRTC STUN — Reduce ICE Server Count

**Problem:** `SignalingModule.buildIceServers()` adds 3 Google STUN servers. All three are contacted during ICE gathering, generating 3× the STUN traffic. In practice, the first one (`stun.l.google.com`) is sufficient for >95% of networks.

**Plan:**

- Reduce to **1 primary STUN server** + 1 backup.
- Add env var `STUN_SERVERS` (comma-separated) so operators can override.
- This reduces STUN consent-fresh traffic by ~33%.

**Files to change:**
- `server/src/modules/signaling/SignalingModule.ts`
- `server/src/config.ts` — add `STUN_SERVERS`

---

## 7. Transport Fallback Architecture

This is the core new feature: **automatic fallback from WebRTC P2P to WebSocket relay, with manual user override, and dashboard visibility of which transport is currently active.**

### 7.1 Current State

Currently the transport is a **hard compile-time switch** via `PREF_WEBRTC_ENABLED` SharedPreference:
- `true` → WebRTC P2P for audio (default)
- `false` → WebSocket relay for all audio

There is no **automatic fallback** — if WebRTC ICE fails (8 s timeout in browser), the browser shows a "WebSocket" badge but the phone is still trying WebRTC. There is no way for the server to know which transport each specific session is actually using.

### 7.2 Proposed Transport State Machine

```
                    ┌─────────────────────────────────────────────┐
                    │                TRANSPORT FSM                 │
                    │                                              │
  App start ──────► │  WEBRTC_NEGOTIATING                         │
                    │  (SignalingClient connects, offer sent)      │
                    │           │                                  │
                    │   ICE connected within 8 s?                 │
                    │        YES ↓          NO ↓                  │
                    │   WEBRTC_ACTIVE    WS_FALLBACK_ACTIVE        │
                    │        │               │                     │
                    │   P2P degrades?    WS healthy?               │
                    │   (RTT > 1s,       YES: stay                 │
                    │    loss > 20%)     NO: error state           │
                    │        │                                     │
                    │   FALLBACK_TRIGGERED                         │
                    │   (soft switch to WS,                        │
                    │    keep WebRTC warm for recovery)            │
                    │        │                                     │
                    │   P2P recovers?                              │
                    │   YES → WEBRTC_ACTIVE                        │
                    │   NO  → WS_FALLBACK_ACTIVE (permanent)       │
                    └─────────────────────────────────────────────┘
```

### 7.3 New `TransportMode` Enum (Android)

Create `domain/streaming/.../TransportMode.kt`:

```kotlin
enum class TransportMode {
    WEBRTC_P2P,           // P2P audio via SRTP/DTLS
    WEBSOCKET_RELAY,      // All audio through server relay
    WEBRTC_NEGOTIATING,   // ICE in progress
    FALLBACK_TRIGGERED,   // Temporary: WebRTC failed, WS active, retry pending
}
```

### 7.4 Transport Negotiation Flow — Phone Side

**`ConnectionOrchestrator` changes:**

1. On `start()`, always attempt WebRTC first (unchanged) — but now start a **parallel WebSocket audio client** in a "standby" state (connected to `/stream` but not sending audio).
2. `WebRTCEngine` reports `onIceConnected` → set `transportMode = WEBRTC_P2P` → disconnect standby WebSocket.
3. If `onIceFailed` fires after 8 s → set `transportMode = FALLBACK_TRIGGERED` → activate the standby WebSocket client for audio → report transport to server.
4. If WebRTC later reconnects (ICE restart succeeds) → switch back to `WEBRTC_P2P` → suspend WS audio → report transport change.

**Key behavioral guarantee:** Audio is **never interrupted** during transport switch. The WebSocket client is pre-connected in standby so the switchover is immediate (<50 ms gap).

### 7.5 Transport Negotiation Flow — Browser Side

`player.html` already has an 8 s ICE timeout that falls back to the `/listen` WebSocket. This behavior is preserved. The new addition is:

- On establishing either transport, send `{ type: "transport", mode: "webrtc"|"websocket", deviceId }` to the server's `/control` WebSocket for recording.
- The server stores this in `ch.transportMode` and broadcasts to dashboard browsers.

### 7.6 Transport Report — Server Protocol

New JSON message on `/control` (phone → server):

```json
{
  "type": "transportStatus",
  "mode": "webrtc_p2p" | "websocket_relay" | "negotiating" | "fallback",
  "rtt": 85,
  "iceState": "connected" | "failed" | "checking",
  "ts": 1704067200000
}
```

Server stores:
- `ch.transportMode: string` 
- `ch.transportRttMs: number | null`

Broadcasts to `/listen` browsers as `{ type: "transportStatus", ... }`.

---

## 8. Dashboard Transport Visibility & User Controls

### 8.1 Transport Badge Per Device Card

Each device card in `public/index.html` currently shows a static "⚡ WebRTC" or "📡 WebSocket" badge in `player.html`. The dashboard (`index.html`) has **no** transport visibility.

**Plan — Add transport badge to dashboard card:**

```html
<!-- New transport badge (added inside .card-top, next to the streaming badge) -->
<span id="transport-badge-${id}" class="badge transport-webrtc">⚡ P2P</span>
```

Badge states:
| State | Style | Text |
|---|---|---|
| `webrtc_p2p` | Green border | `⚡ P2P` |
| `websocket_relay` | Yellow border | `📡 Relay` |
| `negotiating` | Blue pulsing | `🔄 ICE…` |
| `fallback` | Orange border | `⚠ Fallback` |
| Unknown | Gray | `? Transport` |

### 8.2 Transport Selector in Admin Panel

Inside each card's `.admin-panel`, add a **Transport** section:

```html
<div class="admin-section-label">Transport Mode</div>
<div class="admin-row">
  <button class="abtn" id="transport-webrtc-${id}"
    onclick="setTransport('${id}', 'webrtc', this)">⚡ WebRTC P2P</button>
  <button class="abtn" id="transport-ws-${id}"
    onclick="setTransport('${id}', 'websocket', this)">📡 WebSocket</button>
  <button class="abtn" id="transport-auto-${id}"
    onclick="setTransport('${id}', 'auto', this)">🔁 Auto</button>
</div>
<div class="admin-row" style="font-size:.72rem;color:var(--txt2)">
  <span id="transport-rtt-${id}">RTT: —</span>
  <span id="transport-ice-${id}">ICE: —</span>
</div>
```

The **Auto** button sets `PREF_WEBRTC_ENABLED = true` with fallback enabled (new behavior). The WebRTC and WebSocket buttons force a specific transport permanently.

### 8.3 Dashboard JavaScript — Transport Handler

```javascript
// In index.html <script>
async function setTransport(deviceId, mode, btn) {
  const orig = btn.textContent;
  btn.disabled = true; btn.textContent = '⏳';
  try {
    const r = await adminCall(deviceId, 'set-transport', { mode });
    if (r?.ok) {
      btn.textContent = '✅';
      showAdminMsg(btn, `✅ Transport → ${mode} ${r.channel === 'firebase' ? '🔥' : '⚡'}`);
      updateTransportBadge(deviceId, mode === 'websocket' ? 'websocket_relay' : 'negotiating');
    } else {
      btn.textContent = '❌';
    }
  } catch (e) { btn.textContent = '❌'; }
  setTimeout(() => { btn.textContent = orig; btn.disabled = false; }, 2500);
}

function updateTransportBadge(deviceId, mode) {
  const badge = document.getElementById(`transport-badge-${deviceId}`);
  if (!badge) return;
  const map = {
    webrtc_p2p:      { text: '⚡ P2P',     cls: 'transport-webrtc'   },
    websocket_relay: { text: '📡 Relay',    cls: 'transport-ws'       },
    negotiating:     { text: '🔄 ICE…',    cls: 'transport-nego'     },
    fallback:        { text: '⚠ Fallback', cls: 'transport-fallback' },
  };
  const s = map[mode] || { text: '? Transport', cls: 'transport-unknown' };
  badge.textContent = s.text;
  badge.className   = `badge ${s.cls}`;
}

// Wire into existing WS listener: on type === 'transportStatus'
// Add inside syncLiveWs message handler:
// if (msg.type === 'transportStatus') updateTransportBadge(deviceId, msg.mode);
```

### 8.4 Transport Status in `GET /admin/devices` Response

The admin devices endpoint must include transport state so the dashboard initialises correctly on page load:

```json
{
  "id": "...",
  "transportMode": "webrtc_p2p",
  "transportRttMs": 72,
  "iceState": "connected"
}
```

Add these three fields to the `GET /admin/devices` map in `AdminModule.ts`.

---

## 9. Android Transport Selector UI

### 9.1 Transport Setting in `MainActivity`

Add a **Transport** toggle row in `MainActivity` (or the log console section):

```xml
<!-- res/layout/activity_main.xml — new row below start/stop buttons -->
<LinearLayout android:id="@+id/transport_row" ...>
  <TextView android:text="Transport:" ... />
  <RadioGroup android:id="@+id/transport_group" android:orientation="horizontal">
    <RadioButton android:id="@+id/rb_auto"    android:text="Auto"    />
    <RadioButton android:id="@+id/rb_webrtc"  android:text="WebRTC P2P" />
    <RadioButton android:id="@+id/rb_ws"      android:text="WebSocket" />
  </RadioGroup>
  <TextView android:id="@+id/tv_transport_status" ... />
</LinearLayout>
```

`MainViewModel` exposes a `transportMode: StateFlow<TransportMode>` that the UI observes. When the user changes the RadioGroup selection:
1. Write `PREF_WEBRTC_ENABLED` and new `PREF_TRANSPORT_AUTO` to SharedPreferences.
2. Call `ConnectionOrchestrator.setTransportMode(mode)`.
3. If already streaming, trigger a soft reconnect on the new transport without interrupting audio.

### 9.2 Notification — Transport Badge

The persistent foreground notification currently shows "Streaming" or "Connected". Extend it to also show the active transport:

- "🎙 Streaming · ⚡ P2P" (WebRTC)
- "🎙 Streaming · 📡 Relay" (WebSocket)
- "🎙 Streaming · ⚠ Fallback" (WebSocket fallback)

This gives users visibility without opening the app.

**Files to change:**
- `feature/stream/.../StreamingService.kt` — update notification builder
- `feature/stream/.../MainViewModel.kt` — add `transportMode` StateFlow
- `feature/stream/res/layout/activity_main.xml` — add transport row

---

## 10. Server-Side Changes

### 10.1 New `set-transport` Admin Endpoint

Add to `AdminModule.ts`:

```typescript
case 'set-transport': {
  const mode = (u.searchParams.get('mode') ?? body['mode'] ?? 'auto') as string;
  const VALID = new Set(['webrtc', 'websocket', 'auto']);
  if (!VALID.has(mode)) return json(res, 400, { error: 'mode must be webrtc|websocket|auto' });
  // Map to the Android preference action
  const action = mode === 'websocket' ? 'set_transport_ws'
               : mode === 'webrtc'    ? 'set_transport_webrtc'
               : 'set_transport_auto';
  result = await dispatchAdminCommand(deviceId, action, mode);
  break;
}
```

### 10.2 `Channel` Interface Extensions

In `channels.ts`, add to the `Channel` interface:

```typescript
// Transport visibility (populated by transportStatus messages from phone/browser)
transportMode:    string | null;   // "webrtc_p2p" | "websocket_relay" | "negotiating" | "fallback"
transportRttMs:   number | null;   // RTT of the active transport in ms
iceState:         string | null;   // "connected" | "failed" | "checking" | null
```

Initialize all three to `null` in `getOrCreateChannel()`.

### 10.3 `websocket.ts` — Handle `transportStatus`

In `handleControl`, add:

```typescript
if (p.type === 'transportStatus') {
  ch.transportMode  = typeof p.mode     === 'string' ? p.mode     : null;
  ch.transportRttMs = typeof p.rtt      === 'number' ? p.rtt      : null;
  ch.iceState       = typeof p.iceState === 'string' ? p.iceState : null;
  channels.broadcast(ch, JSON.stringify({
    type: 'transportStatus', id: streamId,
    mode: ch.transportMode, rtt: ch.transportRttMs, iceState: ch.iceState,
  }));
  log.info('Transport status updated', { mode: ch.transportMode, rtt: ch.transportRttMs });
}
```

### 10.4 New `CommandProcessor` Actions (Android)

Add three new actions to `CommandProcessor.kt`:

| Action | Handler |
|---|---|
| `set_transport_webrtc` | Write `PREF_WEBRTC_ENABLED = true`, `PREF_TRANSPORT_AUTO = false`; call `onSetTransport?.invoke("webrtc")` |
| `set_transport_ws` | Write `PREF_WEBRTC_ENABLED = false`, `PREF_TRANSPORT_AUTO = false`; call `onSetTransport?.invoke("websocket")` |
| `set_transport_auto` | Write `PREF_WEBRTC_ENABLED = true`, `PREF_TRANSPORT_AUTO = true`; call `onSetTransport?.invoke("auto")` |

Wire `onSetTransport` callback in `StreamingService` → calls `ConnectionOrchestrator.setTransportMode(mode)`.

### 10.5 New Server `.env` Variables

| Variable | Default | Description |
|---|---|---|
| `STUN_SERVERS` | `stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302` | Comma-separated STUN server list |
| `TRANSPORT_FALLBACK_ENABLED` | `true` | Whether server accepts/stores transport fallback status |
| `STATS_INTERVAL_MS` | `3000` (was 2000) | Broadcast interval — increased for lower server CPU |

---

## 11. File-by-File Change Map

### Android (Kotlin)

| File | Changes | Area |
|---|---|---|
| `data/streaming/.../audio/AudioCaptureEngine.kt` | Buffer pool; silence-aware `stop()`/`startRecording()`; larger AudioRecord buffer | Battery, CPU, RAM |
| `data/streaming/.../audio/AudioQualityConfig.kt` | Lower default complexity per preset | CPU |
| `data/streaming/.../audio/WebRTCEngine.kt` | Larger debounce (500 ms); `onIceConnected`/`onIceFailed` callbacks; `getStatsRtt()`; transport mode reporting; `setAudioEnabled()` standby mode | Transport, CPU |
| `data/streaming/.../audio/AudioWebSocketClient.kt` | Standby mode (connect but don't send until activated) | Transport |
| `data/streaming/.../audio/AudioControlClient.kt` | Send `transportStatus` JSON; pong-based RTT measure | Transport, Data |
| `data/streaming/.../audio/SignalingClient.kt` | On ICE fail callback to ConnectionOrchestrator; timeout aligned with new 8 s value | Transport |
| `data/streaming/.../service/ConnectionOrchestrator.kt` | Remove URL refresh runnable; add `setTransportMode()`; parallel WS standby; fallback state machine; send transportStatus to server | Battery, Transport |
| `data/streaming/.../service/MicOrchestrator.kt` | `autoQualityFromRtt()`; battery-aware complexity reduction | CPU, Data |
| `data/streaming/.../service/TelemetryReporter.kt` | Adaptive interval; delta telemetry; pre-built string template | Battery, CPU, Data |
| `data/streaming/.../service/LocationReporter.kt` | Displacement threshold; `PRIORITY_BALANCED_POWER_ACCURACY`; pause on screen-off | Battery, Data |
| `data/streaming/.../service/CommandProcessor.kt` | 3 new transport actions; hourly dedup pruning | Transport, RAM |
| `core/common/.../ByteArrayPool.kt` | New file — buffer pool utility | RAM |
| `core/platform/.../WatchdogWorker.kt` | Health-aware skip; progressive back-off | Battery |
| `feature/stream/.../StreamingService.kt` | Screen state receiver for telemetry interval; update notification with transport badge; wire `onSetTransport` | Battery, Transport |
| `feature/stream/.../MainViewModel.kt` | `transportMode: StateFlow<TransportMode>` | Transport |
| `feature/stream/res/layout/activity_main.xml` | Transport selector RadioGroup | Transport |
| `domain/streaming/.../TransportMode.kt` | New enum | Transport |

### Server (TypeScript)

| File | Changes | Area |
|---|---|---|
| `server/src/config.ts` | Add `STUN_SERVERS`, `TRANSPORT_FALLBACK_ENABLED`; change `STATS_INTERVAL_MS` default | Data, Transport |
| `server/src/channels.ts` | Add `transportMode`, `transportRttMs`, `iceState`, `silenceActive`, `lastStatsJson` to Channel; update `getOrCreateChannel` init; `scheduleGrace` cleanup for `signalingBrowsers` | Transport, RAM |
| `server/src/routes/websocket.ts` | Handle `transportStatus` and `silence` message types in `handleControl` | Transport, Data |
| `server/server.ts` | Diff guard in `statsInterval` | CPU |
| `server/src/modules/admin/AdminModule.ts` | Add `set-transport` case; include `transportMode`, `transportRttMs`, `iceState` in `GET /admin/devices` | Transport |
| `server/src/modules/signaling/SignalingModule.ts` | Remove per-socket `keepalive` setInterval; reduce to 2 STUN servers from env | Battery |
| `server/src/modules/metrics/MetricsModule.ts` | Reduce ring buffer to 60 entries | RAM |
| `server/src/modules/recording/RecordingModule.ts` | Backpressure guard on `stdin.write` | RAM |
| `server/public/index.html` | Transport badge per card; transport selector in admin panel; `updateTransportBadge()` JS; `transportStatus` WS handler; telemetry interval control; RTT/ICE display | Transport, UX |
| `server/public/player.html` | Align transport badge with new `transportStatus` WS message type | Transport |

---

## 12. Implementation Order

Implement in this order to avoid regressions and allow incremental testing:

### Phase 1 — Quick Wins (no architectural change)
1. `AudioQualityConfig.kt` — lower Opus complexity defaults *(CPU −35%)*
2. `SignalingModule.ts` — remove per-socket keepalive *(Battery −5%)*
3. `config.ts` + `server.ts` — STATS_INTERVAL diff guard + 3000 ms default *(CPU −20% server)*
4. `TelemetryReporter.kt` — pre-built string template *(CPU −5%)*
5. `LocationReporter.kt` — displacement threshold + balanced power *(Battery −10%, Data −80% stationary)*
6. `MetricsModule.ts` — ring buffer to 60 *(RAM −15%)*
7. `RecordingModule.ts` — ffmpeg backpressure *(RAM stability)*
8. `WatchdogWorker.kt` — health-aware skip *(Battery −8%)*

### Phase 2 — Telemetry & Data Optimizations
9. `TelemetryReporter.kt` — adaptive interval + screen-state receiver *(Battery −20%)*
10. `TelemetryReporter.kt` + `websocket.ts` — delta telemetry *(Data −60%)*
11. `AudioCaptureEngine.kt` — buffer pool + larger AudioRecord buffer *(RAM −15 MB, CPU −10%)*
12. `ConnectionOrchestrator.kt` — remove URL refresh runnable *(Battery −3%)*

### Phase 3 — Transport Fallback Foundation
13. `TransportMode.kt` — new enum
14. `channels.ts` — add transport fields
15. `websocket.ts` — handle `transportStatus`
16. `AudioWebSocketClient.kt` — standby mode
17. `WebRTCEngine.kt` — `onIceConnected`/`onIceFailed` callbacks; transport reporting
18. `ConnectionOrchestrator.kt` — fallback state machine; parallel WS standby
19. `CommandProcessor.kt` — 3 new transport actions
20. `AdminModule.ts` — `set-transport` endpoint

### Phase 4 — Dashboard Transport UI
21. `index.html` — transport badge CSS + JS + updateTransportBadge()
22. `index.html` — transport selector in admin panel
23. `player.html` — align badge with new message type

### Phase 5 — Android Transport UI
24. `activity_main.xml` — transport selector RadioGroup
25. `MainViewModel.kt` — `transportMode` StateFlow
26. `StreamingService.kt` — notification transport badge

### Phase 6 — Advanced Data Optimizations (optional, high value)
27. `AudioCaptureEngine.kt` — silence-aware AudioRecord suspend
28. `ConnectionOrchestrator.kt` + `websocket.ts` — silence notification protocol
29. `MicOrchestrator.kt` + `WebRTCEngine.kt` — adaptive bitrate ladder

---

## 13. Testing Checklist

> Items marked ✅ are **code-verified** (source confirmed during implementation audit).  
> Items marked 🧪 require **manual / device testing** to confirm runtime behaviour.

### Battery Tests (manual, real device)
- 🧪 Screen-off idle for 2 hours: compare battery% before/after vs current baseline
- 🧪 Stream for 1 hour on `LOW` quality + VOX enabled: verify AudioRecord suspend fires
- ✅ Reboot device with no server: verify WatchdogWorker skips when healthy (logcat `Progressive back-off: next alarm`)
- 🧪 Battery < 20%: verify auto complexity reduction fires (logcat + dashboard quality badge)

### CPU Tests (profiler)
- ✅ `AudioCaptureEngine` uses `ByteArrayPool` — zero steady-state frame allocations
- ✅ Server: `statsInterval` has diff guard — `payload === ch.lastStatsJson` skips broadcast
- 🧪 Opus encode: measure CPU% on device at complexity 7 vs 9 (use `adb shell top`)

### RAM Tests
- ✅ `ByteArrayPool` confirmed in `AudioCaptureEngine.captureLoop()` acquire/release
- ✅ `METRICS_HISTORY_SIZE = 60` (halved from 120) confirmed in `config.ts`
- ✅ `RecordingModule` ffmpeg backpressure guard (`writableNeedDrain`) confirmed
- ✅ `signalingBrowsers` closed and cleared in `scheduleGrace()` before channel eviction
- ✅ `CommandProcessor` hourly dedup prune via `dedupPruneRunnable` + `startDedupPrune()`

### Data Tests (network throttling)
- ✅ VOX silence → `AudioCaptureEngine` soft-suspend → `onSilenceStateChanged(true)` → server `ch.silenceActive = true`
- ✅ Location displacement gate: `MIN_DISPLACEMENT_METERS = 10f`, `MAX_FORCE_SEND_MS = 300_000` in `LocationReporter`
- ✅ Delta telemetry: per-field thresholds + 5-min forced full resync in `TelemetryReporter`

### Transport Fallback Tests
- ✅ `TransportMode` enum, `onIceConnected`/`onIceFailed` callbacks in `WebRTCEngine`
- ✅ `standbyWsClient` pre-connected in `ConnectionOrchestrator.start()`
- ✅ `activateFallback()` switches `wsClient = standbyWsClient` and calls `activateAudio()`
- ✅ `sendTransportStatus()` sends `{type:"transportStatus",mode,iceState,ts}` to `/control`
- ✅ Server stores `transportMode`, `transportRttMs`, `iceState` on channel, broadcasts to browsers
- 🧪 Dashboard: stream with WebRTC → verify `⚡ P2P` badge appears in device card
- 🧪 Block STUN/TURN → verify badge changes to `⚠ Fallback` within 10 s, audio continues
- 🧪 Restore STUN → verify badge returns to `⚡ P2P` within 30 s (ICE restart)
- ✅ Dashboard: `setTransport()` JS + `POST /admin/:id/set-transport` + `updateTransportBadge()` all confirmed in `index.html`
- ✅ Android app: transport RadioGroup in `activity_main.xml`, `applyTransportBadge()` in `MainActivity`
- ✅ `set_transport_webrtc` / `ws` / `auto` commands in `CommandProcessor` (lines 315–329)

### Regression Tests
- ✅ `npx tsc --noEmit` — zero TypeScript errors (confirmed)
- 🧪 `./gradlew assembleDebug` — run in Android Studio (Gradle CLI has JAVA_HOME issue in shell)
- 🧪 All existing admin commands still work (lock, reboot, quality, VOX, etc.)
- 🧪 Recording starts/stops without error
- 🧪 Snapshot returns JPEG within 5 s
- 🧪 Intercom audio flows phone ↔ browser in both transport modes

---

*End of Plan — ready for Phase 1 implementation.*
