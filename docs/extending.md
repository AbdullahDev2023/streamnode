# StreamNode — Extending the System (v5.2 + Optimization & Transport Plan)

## Adding a Server Module

Modules are auto-discovered alphabetically from `src/modules/`. No central registration needed.

### Step 1: Create the file

`server/src/modules/myfeature/MyFeatureModule.ts`

### Step 2: Extend ServerModule

```typescript
import { ServerModule, HttpRouteDefinition, WsRouteDefinition, HealthCheck } from '../../core';
import type { ServerContext } from '../../core/ServerContext';
import { forModule } from '../../logger';

const log = forModule('MyFeature');

export class MyFeatureModule extends ServerModule {
    get name() { return 'MyFeature'; }

    // Optional: declare required env vars — validated before register()
    envSchema() {
        return {
            MY_API_KEY: {
                type: 'string' as const,
                required: true,
                description: 'API key for my external service',
            },
        };
    }

    register(ctx: ServerContext): void {
        // HTTP route
        ctx.addHttpRoute(new HttpRouteDefinition('POST', '/my-endpoint',
            async (req, res) => {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ ok: true }));
            }
        ));

        // WebSocket route
        ctx.addWsRoute(new WsRouteDefinition('/my-ws', (ws, req) => {
            ws.on('message', (data) => { /* handle */ });
        }));

        // Health check
        ctx.addHealthCheck(new HealthCheck('my-feature', async () => ({
            status: 'ok' as const,
            detail: 'everything fine',
        })));

        log.info('MyFeatureModule registered');
    }

    // Optional lifecycle hooks
    async onStart(ctx: ServerContext) { /* start background work */ }
    async onStop(ctx: ServerContext)  { /* cleanup — called LIFO on SIGINT */ }
}

export default MyFeatureModule;
```

### Step 3: Done

Restart the server. The module is live. No changes to `server.ts` or any other file.

**Route priority rule**: Name your module directory so it sorts **before** `streaming/` alphabetically. `StreamingModule` is the wildcard catch-all (`'*'`) and must always be last.

**Lifecycle-only module (no routes)**: If your module only needs startup/shutdown hooks and no HTTP/WS routes, implement only `register()` (with an empty body or a log line) plus `onStart()` / `onStop()`. The `ReconnectModule` is the canonical example — it stamps `serverStartedAt` to Firebase on startup and sends a wake-blast to all known devices, with zero route registrations:

---

## Adding an Admin Command

### Server side

Add a `case` in `AdminModule.ts` `handleAdminRequest` switch:

```typescript
case 'my-action': {
    const param = (u.searchParams.get('value') ?? body['value'] ?? '') as string;
    if (!param) return json(res, 400, { error: 'missing ?value=' });
    result = await dispatchAdminCommand(deviceId, 'admin_myaction', param);
    break;
}
```

Or use the existing generic passthrough (requires `action` to start with `admin_`):
```http
POST /admin/:id/command
Body: { "action": "admin_myaction", "payload": "value" }
```

### Android side

1. Add constant to `AppConstants.kt`:
```kotlin
const val ADMIN_CMD_MY_ACTION = "admin_myaction"
```

2. Handle in `CommandProcessor.process()` inside the `action.startsWith("admin_")` block:
```kotlin
AppConstants.ADMIN_CMD_MY_ACTION -> {
    val param = url.trim()
    MyController.doSomething(context, param)
}
```

Or add a callback pattern (same as `onSnapshot`):
```kotlin
@Volatile var onMyAction: ((String) -> Unit)? = null
// ... in the admin block:
AppConstants.ADMIN_CMD_MY_ACTION -> onMyAction?.invoke(url)
```

Wire the callback in `StreamingService.wireCommandProcessor()`.

---

## Adding a New Telemetry Field

### 1. Android — `TelemetryReporter.kt`

The telemetry system uses **delta encoding** — only fields that changed beyond a threshold are sent each tick. Full resync fires every 5 minutes.

```kotlin
// 1. Add a "last sent" state variable:
private var lastMyField: Int = -1

// 2. Sample the value:
val myValue = readMyMetric()

// 3. Add a change check in the delta section:
val myChanged = forceFullResync || Math.abs(myValue - lastMyField) >= MY_THRESHOLD

// 4. Update cached state when changed:
if (myChanged) lastMyField = myValue

// 5. Append to buildString {} only when changed:
if (myChanged) append(",\"myField\":").append(myValue)
```

### 2. Server — `channels.ts` (Telemetry interface)

```typescript
export interface Telemetry {
    // ... existing fields ...
    myField: number | null;   // add here, initialize to null
}
```

### 3. Server — `routes/websocket.ts` (`handleControl`)

The `handleControl` handler uses **field-level merge** for delta payloads (`p.delta === true`):

```typescript
if (p.type === 'telemetry') {
    // Delta: only merge changed fields; full: replace entirely
    if (p.delta) {
        if (p.myField !== undefined) ch.telemetry.myField = p.myField as number ?? null;
        ch.telemetry.updatedAt = Date.now();
    } else {
        ch.telemetry = {
            ...ch.telemetry,
            myField: p.myField as number ?? null,
            updatedAt: Date.now(),
        };
    }
    channels.broadcast(ch, JSON.stringify({ type:'telemetry', id: streamId, ...ch.telemetry }));
}
```

### 4. Dashboard — `public/index.html` (`buildTelemetryStrip`)

```javascript
const myVal = t.myField != null ? `${t.myField}` : '—';
// Add to strip HTML:
`<span class="t-item">🔩 <span class="t-label">My</span>&nbsp;<span class="t-val">${myVal}</span></span>`
```

---

## Adding a New Android Feature (AppFeature pattern)

1. Implement `AppFeature` interface in `:data:streaming` or a new module:

```kotlin
class MyFeature : AppFeature {
    override val featureId = "my_feature"
    override fun initialize(context: Context) { /* one-time setup */ }
    override fun tearDown() { /* cleanup */ }
}
```

2. Register in `AppGraph` (or `StreamNodeApp.registerFeatures()`):
```kotlin
ServiceLocator.registerFeature(MyFeature())
```

3. Retrieve anywhere:
```kotlin
val feat = ServiceLocator.graph.feature<MyFeature>()
```

---

## Adding a WebRTC Video Track

Tracks are managed in `WebRTCEngine`. Adding a track triggers renegotiation automatically (**500 ms debounce** — batches rapid multi-track adds into a single offer/answer cycle):

```kotlin
// Add tracks (each triggers a new offer/answer exchange)
webRtcEngine.addScreenTrack(resultData, width, height, dpi)  // Screen share
webRtcEngine.addCameraTrack(CameraFacing.FRONT)               // Front camera
webRtcEngine.addCameraTrack(CameraFacing.BACK)                // Back camera (can run simultaneously)

// Remove tracks
webRtcEngine.removeCameraTrack(CameraFacing.FRONT)  // remove one facing only
webRtcEngine.removeAllVideoTracks()                  // removes screen + front + back
```

`addTrack()` → `pc.addTrack(track, listOf(STREAM_ID))` → `onRenegotiationNeeded` → `scheduleRenegotiation()` (**500 ms** debounce) → `createAndSendOffer()`.

**Important**: `addScreenTrack()` takes a raw `Intent` (the MediaProjection permission result), NOT an already-created `MediaProjection`. `ScreenCapturerAndroid` calls `getMediaProjection()` internally. Calling it beforehand causes a `SecurityException` on API 29+.

---

## Server-Side Data Flow for a New WebSocket Message Type

1. **Phone sends**: `audioControlClient.send("""{"type":"myType","value":42}""")`

2. **Server receives** in `routes/websocket.ts` `handleControl()`:
```typescript
if (p.type === 'myType') {
    ch.myField = p.value as number;
    // Optionally persist to channel:
    channels.broadcast(ch, JSON.stringify({
        type: 'myType', id: streamId, value: ch.myField
    }));
    // Optionally record to MetricsModule:
    getMetricsModule()?.recordTelemetry(streamId, ch.telemetry);
}
```

3. **Channel type** — add field to `Channel` interface in `channels.ts`:
```typescript
myField: number | null;
// Initialize in getOrCreateChannel(): myField: null,
```

4. **Browser receives** the broadcast on its `/listen` WebSocket in `syncLiveWs → ws.onmessage`.

---

## Adding a Transport-Aware Command

The transport subsystem exposes three commands that can be dispatched from the dashboard or Firebase:

| Action | Effect |
|---|---|
| `set_transport_webrtc` | Force WebRTC P2P; disables auto-fallback |
| `set_transport_ws` | Force WebSocket relay |
| `set_transport_auto` | Re-enable WebRTC with automatic WS fallback |

These are handled by `CommandProcessor.onSetTransport` → `ConnectionOrchestrator.setTransportMode()`. To add your own transport-dependent behaviour, wire into the `ConnectionOrchestrator.onTransportModeChanged` callback:

```kotlin
// In StreamingService.wireConnectionOrchestrator():
(connections as? ConnectionOrchestrator)?.onTransportModeChanged = { mode ->
    when (mode) {
        TransportMode.WEBRTC_P2P       -> myFeature.enableHighQualityMode()
        TransportMode.WEBSOCKET_RELAY  -> myFeature.enableLowLatencyMode()
        TransportMode.FALLBACK_TRIGGERED -> myFeature.notifyFallback()
        else -> {}
    }
}
```

The current transport is also available from the server via `GET /admin/devices` → `transportMode`, `transportRttMs`, `iceState` fields per device.

---

## Hooking into the Adaptive Bitrate Ladder

`MicOrchestrator.autoQualityFromRtt(rtt: Long)` is the entry point for RTT-driven quality changes. It is wired automatically via `ConnectionOrchestrator.onRttUpdate → StreamingService → micOrchestrator?.autoQualityFromRtt(rtt)`.

To add a custom quality trigger (e.g. based on a different signal than RTT):

```kotlin
// Call from any thread — marshalled internally:
micOrchestrator?.autoQualityFromRtt(measuredRttMs)

// Or set quality directly (bypasses hysteresis):
micOrchestrator?.setQuality(AudioQualityPreset.MEDIUM)
```

The three RTT tiers (< 150 ms → HD, 150–400 ms → MD, > 400 ms → LQ) and 30 s step-up hysteresis are defined as constants in `MicOrchestrator`. Step-down is always immediate; step-up requires the RTT to stay below the threshold for the full hysteresis window.

---

## Using ByteArrayPool for New Audio Paths

If you add a new audio encoding or processing path that allocates short-lived `ByteArray`s in a tight loop, use `ByteArrayPool` from `core:common` to avoid GC pressure:

```kotlin
import com.akdevelopers.streamnode.util.ByteArrayPool

// Acquire before the loop (or before each use):
val buf = ByteArrayPool.acquire(expectedSize)
try {
    // use buf for encoding/processing
    process(buf)
} finally {
    ByteArrayPool.release(buf)  // returns to pool; capped at 8 entries
}
```

The pool reuses buffers if the requested size fits the pooled buffer. Buffers that are too small are discarded and a fresh one is allocated. The pool cap (8 entries) prevents unbounded memory growth.

---

## Adding a New `/control` Message Type

The phone → server message flow for a new type follows this pattern:

1. **Send from Android** (`AudioControlClient` or `AudioControlClient.send()`):
```kotlin
val json = """{"type":"myEvent","value":42,"ts":${System.currentTimeMillis()}}"""
controlClient?.send(json)
```

2. **Handle on server** in `routes/websocket.ts handleControl()`:
```typescript
if (p.type === 'myEvent') {
    ch.myEventField = typeof p.value === 'number' ? p.value : null;
    channels.broadcast(ch, JSON.stringify({
        type: 'myEvent', id: streamId, value: ch.myEventField,
    }));
    log.info('myEvent received', { value: ch.myEventField });
}
```

3. **Add to `Channel`** in `channels.ts`:
```typescript
myEventField: number | null;   // in interface + initialize to null in getOrCreateChannel()
```

4. **Expose in `/admin/devices`** (optional) in `AdminModule.ts`:
```typescript
myEventField: ch.myEventField,
```

5. **Handle in browser** in `index.html` `syncLiveWs → ws.onmessage`:
```javascript
if (msg.type === 'myEvent') {
    document.getElementById(`myevent-${msg.id}`).textContent = msg.value;
}
```

---

Every module can expose a named health check accessible via `GET /health`:

```typescript
ctx.addHealthCheck(new HealthCheck('my-module', async (ctx) => {
    const ok = await checkMyDependency();
    return {
        status: ok ? 'ok' : 'degraded',
        detail: ok ? 'connected' : 'dependency unreachable',
        checkedAt: Date.now(),
    };
}));
```

Health checks run in parallel. `GET /health` aggregates all results and returns 200 (all ok) or 503 (any degraded/error).

---

## Module API Quick Reference

### `ServerContext` — registration API

```typescript
ctx.addHttpRoute(new HttpRouteDefinition(method, path, handler))
// method: 'GET'|'POST'|'PUT'|'DELETE'|'PATCH'|'*'
// path: exact '/foo' | wildcard '*' | RegExp /^\/foo\//

ctx.addWsRoute(new WsRouteDefinition(path, handler))
// path: exact string | '*' | RegExp

ctx.addHealthCheck(new HealthCheck(name, async (ctx) => ({ status: 'ok', ...extra })))
ctx.addStartHook(async (ctx) => { /* runs after server.listen() */ })
ctx.addStopHook(async (ctx) =>  { /* runs on SIGINT, LIFO order */ })
```

### `ServerContext` — available properties

```typescript
ctx.config     // frozen AppConfig (all env vars)
ctx.logger     // { forModule(name): Logger }
ctx.channels   // channel registry (channels.ts exports)
ctx.httpServer // http.Server
ctx.wss        // WebSocket.Server
```

### NPM scripts

| Script | Command | Purpose |
|---|---|---|
| `npm run dev` | `ts-node --transpile-only server.ts` | Dev — no compile step |
| `npm run dev:watch` | nodemon + ts-node | Dev — auto-restart on change |
| `npm run build` | `tsc` | Compile to `dist/` (cleans first) |
| `npm start` | `node dist/server.js` | Run production build |
| `npm run typecheck` | `tsc --noEmit` | Type-check only |
| `npm run clean` | rm `dist/` | Remove compiled output |
