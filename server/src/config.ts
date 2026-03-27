/**
 * src/config.ts — All server configuration constants in one place.
 *
 * Loads environment variables from server/.env (if present) via dotenv.
 * The exported object is frozen — mutations throw in strict mode.
 */
import 'dotenv/config';

// ── Parse & build ─────────────────────────────────────────────────────────────

const PORT                    = parseInt(process.env.PORT                    || '4000',   10);
const WS_PING_INTERVAL_MS     = parseInt(process.env.WS_PING_INTERVAL_MS    || '30000',  10);
const STALE_THRESHOLD_MS      = parseInt(process.env.STALE_THRESHOLD_MS     || '90000',  10);
const STALE_CHECK_MS          = parseInt(process.env.STALE_CHECK_MS         || '15000',  10);
const STATS_INTERVAL_MS       = parseInt(process.env.STATS_INTERVAL_MS      || '3000',   10); // OPT: was 2000 — 3s is imperceptible on dashboard, saves CPU
const CHANNEL_GRACE_MS        = parseInt(process.env.CHANNEL_GRACE_MS       || '300000', 10);
const MAX_REQUESTS_PER_WINDOW = parseInt(process.env.MAX_REQUESTS_PER_WINDOW || '120',    10);
const RATE_WINDOW_MS          = parseInt(process.env.RATE_WINDOW_MS         || '60000',  10);
const SERVER_VOLUME_GAIN      = parseFloat(process.env.SERVER_VOLUME_GAIN   || '3.0');
const LOG_LEVEL               = (process.env.LOG_LEVEL || 'INFO').toUpperCase();
const METRICS_HISTORY_SIZE    = parseInt(process.env.METRICS_HISTORY_SIZE   || '60',     10); // OPT: was 120 — 60 = 1h at 60s; halves per-device RAM
const RECONNECT_ON_STARTUP    = process.env.RECONNECT_ON_STARTUP !== 'false'; // default true
const MAX_WAKE_DEVICES        = parseInt(process.env.MAX_WAKE_DEVICES        || '50',     10);
// Comma-separated STUN server URLs.
// Default = 6 servers from 3 independent providers (Google ×5, Cloudflare ×1).
// More servers = more NAT binding paths probed in parallel = higher ICE success rate.
// All are queried simultaneously; the first usable candidate pair wins.
// Override via STUN_SERVERS env var for custom/private STUN infrastructure.
const STUN_SERVERS            = process.env.STUN_SERVERS
    || [
        'stun:stun.l.google.com:19302',
        'stun:stun1.l.google.com:19302',
        'stun:stun2.l.google.com:19302',
        'stun:stun3.l.google.com:19302',
        'stun:stun4.l.google.com:19302',
        'stun:stun.cloudflare.com:3478',
    ].join(',');

// ── Startup validation ────────────────────────────────────────────────────────

const VALID_LOG_LEVELS = new Set(['DEBUG', 'INFO', 'WARN', 'ERROR']);
if (!VALID_LOG_LEVELS.has(LOG_LEVEL)) {
    throw new Error(`config: LOG_LEVEL must be one of DEBUG|INFO|WARN|ERROR, got "${LOG_LEVEL}"`);
}
if (isNaN(PORT) || PORT < 1 || PORT > 65535) {
    throw new Error(`config: PORT must be an integer 1-65535, got "${process.env.PORT}"`);
}
if (STALE_THRESHOLD_MS <= STALE_CHECK_MS) {
    throw new Error(
        `config: STALE_THRESHOLD_MS (${STALE_THRESHOLD_MS}) must be greater than ` +
        `STALE_CHECK_MS (${STALE_CHECK_MS})`
    );
}
if (WS_PING_INTERVAL_MS < 5000) {
    throw new Error(`config: WS_PING_INTERVAL_MS must be >= 5000ms, got ${WS_PING_INTERVAL_MS}`);
}

// ── STUN-only enforcement ─────────────────────────────────────────────────────
// AuraCast uses STUN for direct ICE negotiation + WebSocket relay as the
// symmetric-NAT fallback.  TURN is permanently disabled: it adds external cost
// and infra dependency without benefit because the WS relay already covers every
// case TURN would handle (ICE FAILED → <50 ms automatic switchover to WS relay).
//
// Fail fast if someone accidentally sets TURN env vars from an old .env snapshot.
for (const varName of ['TURN_URL', 'TURN_USERNAME', 'TURN_CREDENTIAL'] as const) {
    if (process.env[varName]) {
        throw new Error(
            `[AuraCast] TURN is disabled — "${varName}" must not be set.\n` +
            `  Symmetric NAT is handled by the WebSocket relay fallback (<50 ms switchover).\n` +
            `  Remove "${varName}" from your .env and restart.`
        );
    }
}

// ── Export type ───────────────────────────────────────────────────────────────

export interface AppConfig {
    readonly PORT:                    number;
    readonly FIREBASE_DB_URL:         string;
    readonly FIREBASE_SECRET:         string;
    readonly WS_PING_INTERVAL_MS:     number;
    readonly STALE_THRESHOLD_MS:      number;
    readonly STALE_CHECK_MS:          number;
    readonly STATS_INTERVAL_MS:       number;
    readonly CHANNEL_GRACE_MS:        number;
    readonly SERVER_VOLUME_GAIN:      number;
    readonly LOG_LEVEL:               string;
    readonly RATE_LIMIT_ENABLED:      boolean;
    readonly MAX_REQUESTS_PER_WINDOW: number;
    readonly RATE_WINDOW_MS:          number;
    readonly RECORDINGS_DIR:          string;
    readonly METRICS_HISTORY_SIZE:    number;
    /** Gap 6: send Firebase reconnect command to all known devices on server startup. */
    readonly RECONNECT_ON_STARTUP:    boolean;
    /** Max devices to wake in one startup blast (prevents Firebase quota exhaustion). */
    readonly MAX_WAKE_DEVICES:        number;
    /** §6.5: Comma-separated STUN server URLs (default: 2 Google servers). */
    readonly STUN_SERVERS:            string;
}

// ── Export (frozen) ───────────────────────────────────────────────────────────

const config: AppConfig = Object.freeze({
    PORT,
    FIREBASE_DB_URL: process.env.FIREBASE_DB_URL
        || 'https://auracast-df815-default-rtdb.asia-southeast1.firebasedatabase.app',
    FIREBASE_SECRET: process.env.FIREBASE_DB_SECRET  || '',
    WS_PING_INTERVAL_MS,
    STALE_THRESHOLD_MS,
    STALE_CHECK_MS,
    STATS_INTERVAL_MS,
    CHANNEL_GRACE_MS,
    SERVER_VOLUME_GAIN,
    LOG_LEVEL,
    RATE_LIMIT_ENABLED:      process.env.RATE_LIMIT_ENABLED === 'true',
    MAX_REQUESTS_PER_WINDOW,
    RATE_WINDOW_MS,
    RECORDINGS_DIR:  process.env.RECORDINGS_DIR  || './recordings',
    METRICS_HISTORY_SIZE,
    RECONNECT_ON_STARTUP,
    MAX_WAKE_DEVICES,
    STUN_SERVERS,
});

export default config;
