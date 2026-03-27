# StreamNode — Operations Guide (v5.2 + Optimization & Transport Plan)

## Server Setup

### Prerequisites

- Node.js 20+ (LTS recommended)
- npm 9+
- ffmpeg in PATH (required only for server-side recording)
- ngrok account (free tier works; or use Render.com for permanent deployment)

### Installation

```bash
cd server
npm install
cp .env.example .env
```

### Edit `server/.env`

```env
PORT=4000
FIREBASE_DB_URL=https://streamnode-df815-default-rtdb.asia-southeast1.firebasedatabase.app
FIREBASE_DB_SECRET=<your-firebase-database-secret>

# Optional: TURN relay for symmetric NAT (most deployments don't need this)
TURN_URL=
TURN_USERNAME=
TURN_CREDENTIAL=

# Optional: recording output directory (default: server/recordings/)
RECORDINGS_DIR=./recordings

# Optional: log verbosity (DEBUG|INFO|WARN|ERROR, default: INFO)
LOG_LEVEL=INFO
```

> **No ADMIN_SECRET needed.** All `/admin/*` endpoints are open by design.
> `adminAuth` middleware unconditionally returns `true`.

### Firebase Admin SDK

Required for: `/wake` endpoint, offline command delivery when phone WebSocket is closed.

1. Firebase Console → Project Settings → Service Accounts → **Generate new private key**
2. Save as `server/config/serviceAccount.json` — **never commit this file** (it is gitignored)

Without `serviceAccount.json`, commands can still be dispatched over the live control WebSocket when the phone is connected.

### Start Server

```bash
# Dev mode (ts-node, no compile):
cd server && npm run dev

# Dev with file-watch auto-restart:
npm run dev:watch

# Production (compiled):
npm run build && npm start

# Windows all-in-one (server + ngrok + browser):
scripts\start-streamnode.bat
```

### Environment Variables Reference

| Variable | Default | Type | Description |
|---|---|---|---|
| `PORT` | `4000` | int | HTTP/WS listen port |
| `FIREBASE_DB_URL` | hardcoded | string | Firebase RTDB URL |
| `FIREBASE_DB_SECRET` | `""` | string | Legacy REST secret for Firebase fallback |
| `WS_PING_INTERVAL_MS` | `30000` | int | WebSocket heartbeat ping interval (min 5000) |
| `STALE_THRESHOLD_MS` | `90000` | int | Idle phone socket eviction time (must > STALE_CHECK_MS) |
| `STALE_CHECK_MS` | `15000` | int | Stale socket check interval |
| `STATS_INTERVAL_MS` | `3000` | int | Stats broadcast interval; skips unchanged payloads (was 2000) |
| `CHANNEL_GRACE_MS` | `300000` | int | Channel TTL after all phone sockets close |
| `SERVER_VOLUME_GAIN` | `3.0` | float | Audio gain multiplier sent to browsers |
| `LOG_LEVEL` | `INFO` | string | `DEBUG\|INFO\|WARN\|ERROR` |
| `RATE_LIMIT_ENABLED` | `false` | bool | Per-IP rate limiting |
| `MAX_REQUESTS_PER_WINDOW` | `120` | int | Max requests per IP per window |
| `RATE_WINDOW_MS` | `60000` | int | Rate limit window size |
| `RECORDINGS_DIR` | `./recordings` | string | Directory for .ogg recordings |
| `METRICS_HISTORY_SIZE` | `60` | int | Ring buffer entries per device (1 h at 60 s intervals) |
| `TURN_URL` | `""` | string | TURN server URL (e.g. `turn:host:3478`) |
| `TURN_USERNAME` | `""` | string | TURN credential username |
| `TURN_CREDENTIAL` | `""` | string | TURN credential password |
| `STUN_SERVERS` | `stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302` | string | Comma-separated STUN URLs (2 by default) |
| `TRANSPORT_FALLBACK_ENABLED` | `true` | bool | Store/broadcast transport fallback status |
| `RECONNECT_ON_STARTUP` | `true` | bool | Stamp `serverStartedAt` + wake-blast all devices on startup |
| `MAX_WAKE_DEVICES` | `50` | int | Max devices in one startup wake blast |

---

## Android App Setup

### Build

```bat
:: Uses C:\jbr21 (OpenJDK 21) — adjust if needed
scripts\build_debug_fix.bat

:: Or with any JDK 17+
set JAVA_HOME=C:\Program Files\Java\jdk-17
.\gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Install and First Launch

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.akdevelopers.streamnode/.ui.setup.SetupActivity
```

### Setup Wizard (4 steps, mandatory)

1. **Runtime permissions**: `RECORD_AUDIO`, `CAMERA`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, optional `ACCESS_FINE_LOCATION`
2. **Battery optimisation exemption**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prevents Android from killing the service)
3. **OEM autostart**: Deep-links to manufacturer battery/autostart settings (Xiaomi, OPPO, Huawei, Samsung)
4. **Device Admin enrollment** (skippable): `DevicePolicyManager.addDeviceAdminService()`

### Firebase RTDB Config

In Firebase Console → Realtime Database, set:

```
/streamnode_config/serverUrl_v2 = "wss://your-ngrok-subdomain.ngrok-free.app/stream"
```

The app reads this on every launch. Falls back to `AppConstants.DEFAULT_SERVER_URL` if Firebase is unavailable.

Must use `wss://` (not `ws://`) for production ngrok/Render deployments. For local dev use `ws://10.0.2.2:4000/stream` (Android emulator) or `ws://192.168.x.x:4000/stream`.

### Device Owner Provisioning (optional, one-time)

Unlocks: reboot, clear app data, uninstall, brightness control, silent Device Admin operations.

```bash
# Requirements: USB debugging on, no Google accounts on device
adb shell dpm set-device-owner \
  com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver

# Or use the provided script:
scripts\provision-device-owner.bat
```

---

## Admin HTTP API

All endpoints at `http://localhost:4000` (or your ngrok/production URL). No authentication required.

### Device List

```http
GET /admin/devices
→ [
    {
      id, displayName,
      phoneConnected, streamingActive,
      listeners,     // /listen browser count
      telemetry,     // full telemetry object
      location,      // latest GPS fix
      stats,         // framesRelayed, bytesRelayed, connectedAt
      codecConfig    // Opus params
    }, ...
  ]
```

### Device Commands

All `POST /admin/:id/<action>`. Returns:
- `200 { ok, commandId, channel:"websocket", action, payload }` — dispatched live
- `202 { ok, commandId, channel:"firebase", action, payload, note }` — queued (phone offline)
- `409 { error }` — phone offline and Firebase not configured, or snapshot timeout

| Endpoint | Parameters | Description |
|---|---|---|
| `POST /admin/:id/lock` | — | Lock device screen |
| `POST /admin/:id/reboot` | — | Reboot (Device Owner only) |
| `POST /admin/:id/wipe` | `?confirm=WIPE` (required) | Factory reset |
| `POST /admin/:id/camera` | `?enable=true\|false` | Toggle camera policy |
| `POST /admin/:id/brightness` | `?value=0-255` | Set screen brightness |
| `POST /admin/:id/volume` | `?value=0-15` | Set media volume |
| `POST /admin/:id/password` | `?password=<new>` | Reset device password |
| `POST /admin/:id/clear-app` | `?package=com.example` | Clear app data |
| `POST /admin/:id/uninstall` | `?package=com.example` | Uninstall package |
| `POST /admin/:id/max-fails` | `?value=10` | Max failed-password wipes |
| `POST /admin/:id/set-quality` | `?level=HIGH_QUALITY\|MEDIUM\|LOW` | Audio quality preset |
| `POST /admin/:id/set-sample-rate` | `?rate=8000\|16000\|32000\|48000` | Audio sample rate |
| `POST /admin/:id/set-vbr` | `?enabled=true\|false` | VBR/CBR toggle |
| `POST /admin/:id/set-frame-ms` | `?ms=2\|5\|10\|20\|40\|60` | Opus frame duration |
| `POST /admin/:id/set-vox` | `?enabled=bool&threshold=num` (or JSON body) | VOX silence gate |
| `POST /admin/:id/set-internal-audio` | `?enabled=bool&mixWithMic=bool` | Internal audio capture (API 29+) |
| `POST /admin/:id/set-audio-config` | JSON body (see below) | Full custom audio config |
| `POST /admin/:id/snapshot` | — | Capture JPEG (5 s timeout) → returns `image/jpeg` |
| `POST /admin/:id/torch` | `?on=true\|false` | Flashlight toggle |
| `POST /admin/:id/set-transport` | `?mode=webrtc\|websocket\|auto` | Pin or release transport mode; dispatches `set_transport_*` command |
| `POST /admin/:id/command` | JSON body `{ action, payload }` | Generic `admin_*` passthrough |
| `GET /admin/:id/status` | — | Firebase adminStatus `{ isDeviceAdmin, isDeviceOwner }` |

#### `set-audio-config` JSON body

```json
{
  "sampleRate": 48000,
  "bitrate":    192000,
  "frameMs":    60,
  "vbr":        false,
  "complexity": 9,
  "agc":        false,
  "ns":         false,
  "aec":        false,
  "voxThreshold": 0
}
```

Validation: `sampleRate` must be 8000|16000|32000|48000; `frameMs` must be 2|5|10|20|40|60; `bitrate` 6000–510000; `complexity` 0–10.

### Recording Endpoints

| Endpoint | Response |
|---|---|
| `POST /admin/:id/record/start` | `{ ok, filePath, startedAt }` |
| `POST /admin/:id/record/stop` | `{ ok, filePath, durationSec }` |
| `GET /admin/:id/record/status` | `{ active, filePath, startedAt, durationSec }` |
| `GET /recordings/:filename` | OGG file download |

ffmpeg must be in PATH. Recording saves raw Opus frames into an OGG container at `RECORDINGS_DIR`.

### Metrics Endpoints

| Endpoint | Description |
|---|---|
| `GET /metrics/:id/history?limit=30` | Last N telemetry snapshots (JSON array, max 120) |
| `GET /metrics/:id/stream` | Server-Sent Events — live telemetry stream |
| `GET /metrics/summary` | All devices' latest telemetry + history count |

### Other HTTP Endpoints

| Endpoint | Description |
|---|---|
| `GET /` | Dashboard (index.html) |
| `GET /player?id=` | Audio player + intercom page |
| `GET /pip?id=` | Dual-camera PiP page |
| `GET /screen-view?id=` | Screen share viewer |
| `GET /camera-front-view?id=` | Front camera viewer |
| `GET /camera-back-view?id=` | Back camera viewer |
| `GET /streams` | Active channel list (JSON) |
| `GET /health` | Server health + streaming-channels stats |
| `GET /ice-config` | WebRTC ICE server list (STUN + optional TURN) |
| `GET /webrtc-status` | Active signaling sessions |
| `POST /stream-control?id=&action=` | Legacy command HTTP endpoint |
| `POST /wake?id=` | Send reconnect command via Firebase Admin |

### WebSocket Endpoints

| Path | Direction | Protocol | Description |
|---|---|---|---|
| `/stream?id=&name=` | Phone → Server | Binary | Opus audio frames (legacy) |
| `/control?id=&name=` | Bidirectional | JSON + Binary | Commands, telemetry, snapshot |
| `/listen?id=` | Server → Browser | Binary + JSON | Audio relay + status/telemetry broadcast |
| `/signal?id=&role=phone\|browser` | Bidirectional | JSON | WebRTC SDP/ICE signaling relay |
| `/screen?id=&name=` | Phone → Server | Binary | H.264 screen capture (legacy) |
| `/screen-watch?id=` | Server → Browser | Binary | Screen relay |
| `/camera-front?id=` | Phone → Server | Binary | H.264 front camera (legacy) |
| `/camera-back?id=` | Phone → Server | Binary | H.264 back camera (legacy) |
| `/camera-front-watch?id=` | Server → Browser | Binary | Front camera relay |
| `/camera-back-watch?id=` | Server → Browser | Binary | Back camera relay |

---

## TURN Configuration

Required only for devices behind symmetric NAT (affects ~20% of networks).

```env
TURN_URL=turn:your-turn-server.example.com:3478
TURN_USERNAME=username
TURN_CREDENTIAL=password
```

Free TURN options:
- **Cloudflare Calls API** — 1000 min/month free
- **Metered.ca** — 500 GB/month free
- **Self-host coturn** — `apt install coturn`

---

## ngrok Setup

```bash
# Install ngrok, authenticate with your authtoken, then:
ngrok http 4000

# With a fixed subdomain (ngrok paid plan or free static domain):
ngrok http --domain=yourname.ngrok-free.app 4000
```

Update Firebase RTDB `serverUrl_v2` after each ngrok restart (unless using a fixed domain).

The `start-streamnode.bat` script uses a hardcoded static ngrok domain:
`noniridescently-glyphographic-brant.ngrok-free.dev` — replace with your own.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| App connects but stays "Connected", no streaming | Server never sent `start` command | Click **▶ Start** on dashboard card |
| Dashboard card not appearing | Firebase `serverUrl_v2` not set | Check Firebase RTDB; update URL to ngrok `wss://` |
| `ICE FAILED` in app log | Symmetric NAT, no TURN | Configure `TURN_URL/USERNAME/CREDENTIAL` in `.env` |
| Snapshot times out (5 s) | Camera2 busy (another app holds camera) | Close other camera apps, retry |
| Recording fails / ffmpeg error | ffmpeg not in PATH | Install ffmpeg; verify `ffmpeg -version` in terminal |
| `/wake` returns 503 | `serviceAccount.json` missing | Place Firebase Admin key at `server/config/serviceAccount.json` |
| Build fails: JAVA_HOME invalid | Wrong JDK path | Use `scripts\build_debug_fix.bat` (sets `C:\jbr21`) |
| App crashes on LOCKED_BOOT | SharedPreferences unavailable pre-unlock | Expected — `StreamNodeApp` defers until fully unlocked |
| `admin_reboot` does nothing | Not Device Owner | Run `scripts\provision-device-owner.bat` |
| Phone offline, commands not delivered | No `serviceAccount.json` + phone WS closed | Add Firebase Admin key for offline delivery |
| WebRTC badge shows 📡 WebSocket | ICE timeout (8 s) or WebRTC disabled | Check STUN reachability; add TURN for symmetric NAT |
| `set-vox` has no effect | MicOrchestrator not started (not streaming) | Start audio streaming first, then apply VOX |
| Internal audio capture fails | Phone below API 29 or no MediaProjection | Requires Android 10+; must be in screen-share mode |
| `admin_camera_disable` does nothing | Not Device Admin | Complete Setup Wizard Step 4 |
| Battery alert not showing | Telemetry not flowing | Check `/control` WS connection; verify `FIREBASE_DB_SECRET` is set |
| After server restart, phone takes 5 min to reconnect | `serviceAccount.json` missing — `ReconnectModule` cannot write `serverStartedAt` | Place Firebase Admin key; server will stamp `serverStartedAt` on next start and wake devices in <3 s |
| Device shows `online: true` in Firebase after crash | `onDisconnect()` not yet executed by Firebase servers | Wait 60-90 s — Firebase servers execute the handler automatically after TCP timeout |
| Wake blast log: `succeeded: 0` | `FIREBASE_DB_SECRET` not set in `.env` | Set `FIREBASE_DB_SECRET` — required for `writeFirebaseCommand` REST writes |
| Exact alarm not granted (API 31-32) | User dismissed the alarm dialog at wizard end | Go to Settings → Apps → StreamNode → Alarms & Reminders → allow |

---

## Log Levels

Set `LOG_LEVEL` in `server/.env`. Default: `INFO`.

| Level | What is logged |
|---|---|
| `DEBUG` | Full WS message traces, ICE candidates, frame counts, telemetry builds |
| `INFO` | Connections, disconnections, commands, module load, recording lifecycle |
| `WARN` | Stale sockets evicted, fallback paths, low battery alerts, ICE FAILED |
| `ERROR` | Uncaught exceptions, Firebase write failures, ffmpeg spawn errors |
