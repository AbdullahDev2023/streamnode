# StreamNode Module System (TypeScript v5.2)

Zero-central-file plugin architecture. Drop a `.ts` file in `src/modules/` — the server auto-discovers it on next restart.

---

## Directory Layout

```
server/
  server.ts                          ← thin bootstrap (never modify for features)
  src/
    config.ts                        ← all env vars, frozen AppConfig object
    channels.ts                      ← Channel type + registry + lifecycle
    logger.ts                        ← forModule() structured logger
    firebase.ts                      ← Firebase Admin SDK init
    core/
      index.ts                       ← public re-export of all core types
      ServerModule.ts                ← abstract base class
      ServerContext.ts               ← DI container + dispatcher
      HttpRouteDefinition.ts         ← typed HTTP route value object
      WsRouteDefinition.ts           ← typed WS route value object
      HealthCheck.ts                 ← typed health check value object
      EnvConfig.ts                   ← schema-driven env validator
      ModuleLoader.ts                ← auto-discovers + loads modules
    modules/
      admin/AdminModule.ts           ← /admin/* device-control API
      camera/CameraModule.ts         ← front/back camera WS relay
      metrics/MetricsModule.ts       ← telemetry ring buffer + SSE
      recording/RecordingModule.ts   ← server-side Opus/OGG recording
      screen/ScreenModule.ts         ← screen share WS relay
      signaling/SignalingModule.ts   ← WebRTC SDP/ICE relay
      streaming/StreamingModule.ts   ← catch-all HTTP + WS (must be last)
      yourFeature/YourModule.ts      ← add yours here — zero central changes
    routes/
      http.ts                        ← HTTP catch-all handler (serves pages, streams list, etc.)
      websocket.ts                   ← WS dispatcher: /stream /control /listen
    middleware/
      adminAuth.ts                   ← unconditionally returns true (open API)
      cors.ts                        ← CORS headers + preflight
      requestId.ts                   ← X-Request-Id header
      validate.ts                    ← query/body param helpers
    services/
      metrics.ts                     ← server process metrics
      rateLimit.ts                   ← per-IP token bucket (off by default)
    types/
      http.d.ts                      ← IncomingMessage augmentation
      ws.d.ts                        ← WebSocket augmentation
  public/
    index.html                       ← Dashboard
    player.html                      ← WebRTC audio player + intercom
    pip.html                         ← Dual-camera PiP
    screen.html                      ← Screen share viewer
    camera.html                      ← Single camera viewer
```

---

## Module Load Order

Modules load **alphabetically**. Route matching is **first-match-wins**.

```
admin     → HTTP /admin/* (regex excludes /admin/:id/record/* for RecordingModule)
camera    → WS /camera-front|back + HTTP camera viewer pages
metrics   → HTTP /metrics/:id/history|stream + /metrics/summary
reconnect → no routes — lifecycle hooks: onStart() stamps serverStartedAt + wake-blast; onStop() writes serverOnline=false
recording → HTTP /admin/:id/record/{start,stop,status}
screen    → WS /screen|screen-watch + HTTP screen-view page
signaling → HTTP /ice-config|/webrtc-status + WS /signal
streaming → catch-all HTTP '*' + catch-all WS '*'  ← ALWAYS LAST
```

**Name your module so it sorts before `streaming/`** to guarantee it takes priority.

---

## Quick Start: Adding a Module

```typescript
// src/modules/metrics/MetricsModule.ts
import { ServerModule, HttpRouteDefinition, HealthCheck } from '../../core';
import type { ServerContext } from '../../core/ServerContext';
import { forModule } from '../../logger';

const log = forModule('Metrics');

export class MetricsModule extends ServerModule {
    get name() { return 'Metrics'; }

    register(ctx: ServerContext): void {
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/metrics',
            (_req, res) => {
                res.writeHead(200, { 'Content-Type': 'text/plain' });
                res.end(`uptime_seconds ${Math.floor(process.uptime())}\n`);
            }
        ));

        ctx.addHealthCheck(new HealthCheck('metrics', async () => ({
            status: 'ok' as const,
            uptimeSec: Math.floor(process.uptime()),
        })));

        log.info('MetricsModule registered');
    }
}

export default MetricsModule;
```

Restart the server — the route is live. No other files changed.

---

## API Reference

### `ServerModule` (abstract base)

| Member | Required | Purpose |
|---|---|---|
| `get name()` | **yes** | Label in logs |
| `envSchema()` | no | Declare required env vars — validated before `register()` |
| `register(ctx)` | **yes** | Register routes / health checks / hooks |
| `onStart(ctx)` | no | Async init after `server.listen()` fires |
| `onStop(ctx)` | no | Async teardown on `SIGINT` (LIFO order) |

### `ServerContext` — registration methods

```typescript
ctx.addHttpRoute(new HttpRouteDefinition(method, path, handler))
ctx.addWsRoute(new WsRouteDefinition(path, handler))
ctx.addHealthCheck(new HealthCheck(name, async (ctx) => ({ status: 'ok', ...extra })))
ctx.addStartHook(async (ctx) => { /* after listen */ })
ctx.addStopHook(async (ctx) =>  { /* before exit — LIFO */ })
```

### `ServerContext` — available properties

```typescript
ctx.config     // frozen AppConfig (all env vars from config.ts)
ctx.logger     // { forModule(name: string): Logger }
ctx.channels   // channel registry (Map + helpers from channels.ts)
ctx.httpServer // http.Server
ctx.wss        // WebSocket.Server
```

### `HttpRouteDefinition`

```typescript
new HttpRouteDefinition(method, path, handler)
// method:  'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | '*'
// path:    exact '/foo' | wildcard '*' | RegExp /^\/foo\//
// handler: async (req: IncomingMessage, res: ServerResponse, ctx: ServerContext) => void
```

### `WsRouteDefinition`

```typescript
new WsRouteDefinition(path, handler)
// path:    exact string | '*' | RegExp
// handler: (ws: WebSocket, req: IncomingMessage, ctx: ServerContext) => void
```

### `EnvConfig.envSchema()`

Modules declare env vars via `envSchema()`. `ModuleLoader` calls `EnvConfig.applySchema()` before `register()`. Missing required vars throw at startup with a clear error message.

```typescript
envSchema() {
    return {
        MY_API_KEY: {
            type: 'string' as const,
            required: true,
            description: 'API key for my external service',
        },
        MY_TIMEOUT_MS: {
            type: 'number' as const,
            required: false,
            description: 'Request timeout in ms (default 5000)',
        },
    };
}
```

---

## Existing Modules Summary

| Module | Routes | Notes |
|---|---|---|
| `AdminModule` | HTTP `/admin/*` | All device commands; regex negative-lookahead excludes `/admin/:id/record/*` |
| `CameraModule` | WS `/camera-front\|back`, `/camera-*-watch` | Legacy H.264 camera relay |
| `MetricsModule` | HTTP `/metrics/:id/history`, `/metrics/:id/stream` (SSE), `/metrics/summary` | Ring buffer (120 snapshots), SSE live stream; singleton accessible via `getMetricsModule()` |
| `ReconnectModule` | None (lifecycle-only) | `onStart()`: writes `serverStartedAt` + `serverOnline=true` to Firebase; sends `reconnect` command to all `/users/` devices. `onStop()`: writes `serverOnline=false`. Controlled by `RECONNECT_ON_STARTUP` env var. |
| `RecordingModule` | HTTP `/admin/:id/record/{start,stop,status}` | ffmpeg OGG recording; uses `process.cwd()` for path resolution |
| `ScreenModule` | WS `/screen`, `/screen-watch`; HTTP `/screen-view` | Legacy H.264 screen relay |
| `SignalingModule` | HTTP `/ice-config`, `/webrtc-status`; WS `/signal` | WebRTC SDP/ICE relay; 20 s server-side keepalive ping on phone socket |
| `StreamingModule` | HTTP `*`, WS `*` | Catch-all; wraps `routes/http.ts` and `routes/websocket.ts`; must be alphabetically last |

---

## NPM Scripts

| Script | Command | Purpose |
|---|---|---|
| `npm run dev` | `ts-node --transpile-only server.ts` | Dev — no compile step, instant start |
| `npm run dev:watch` | nodemon + ts-node | Dev — auto-restart on `.ts` file change |
| `npm run build` | `tsc` (prebuild cleans `dist/`) | Compile TypeScript → `dist/` |
| `npm start` | `node dist/server.js` | Run compiled production build |
| `npm run typecheck` | `tsc --noEmit` | Type-check without output |
| `npm run lint` | `tsc --noEmit && echo Lint OK` | Alias for typecheck |
| `npm run clean` | rm `dist/` | Remove build artifacts |
