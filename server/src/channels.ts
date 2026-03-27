/**
 * src/channels.ts — Channel Map lifecycle manager (v6).
 *
 * Includes all v6 feature fields:
 *   recording (Feature 3), telemetry (Feature 4),
 *   snapshotResolve (Feature 6), location (Feature 9),
 *   signalingPhone / signalingBrowsers (WebRTC),
 *   featureFlags (v6 FeatureFlags system).
 */
import WebSocket from 'ws';
import config from './config';
import { forModule } from './logger';

const logger = forModule('Channels');

// ── Types ─────────────────────────────────────────────────────────────────────

export interface ChannelStats {
    framesRelayed:  number;
    bytesRelayed:   number;
    connectedAt:    number | null;
    disconnectedAt: number | null;
    lastActivityMs: number;
}

/**
 * Codec configuration announced by the phone on /stream connect.
 * Feature 2: sampleRate is now runtime-configurable (8000|16000|32000|48000).
 */
export interface CodecConfig {
    codec:      string;   // always "opus"
    sampleRate: number;   // 8000 | 16000 | 32000 | 48000
    channels:   number;   // 1 (mono)
    frameMs?:   number;   // Opus frame size in ms
    bitrate?:   number;   // target kbps if reported
}

export interface Recording {
    active:     boolean;
    ffmpegProc: import('child_process').ChildProcess | null;
    filePath:   string | null;
    startedAt:  number | null;
    frameHook:  ((frame: Buffer) => void) | null;
}

export interface Telemetry {
    batteryLevel: number | null;
    charging:     boolean;
    signalDbm:    number | null;
    cpuTempC:     number | null;
    wifiSsid:     string | null;
    updatedAt:    number | null;
    // Feature 3: VOX silence-gate metrics
    voxDropRate:  number | null;   // 0.0–1.0 (frames dropped / total) during last interval
    voxEnabled:   boolean | null;  // true = gate active
    voxThreshold: number | null;   // RMS threshold value
    // Feature 9: expanded device + streaming metrics
    usedRamMB:       number | null;  // app process used heap MB
    totalRamMB:      number | null;  // app process max heap MB
    netType:         string | null;  // "wifi" | "cellular" | "ethernet" | "unknown"
    linkSpeedMbps:   number | null;  // Wi-Fi link speed in Mbps (-1 if unavailable)
    screenOn:        boolean | null; // device screen interactive state
    streamFps:       number | null;  // Opus frames per second from MicOrchestrator
    streamKbps:      number | null;  // encoded audio kbps from MicOrchestrator
    streamUptimeSec: number | null;  // seconds since StreamingService started
}

/**
 * FeatureFlags — v6 unified feature toggle state.
 * Sent from dashboard via POST /admin/:id/feature-flags and forwarded to the phone.
 * Also stored per-channel so GET /admin/devices can include the current flag state.
 */
export interface FeatureFlags {
    vbrEnabled?:           boolean;
    frameSizeMs?:          number;   // 2 | 5 | 10 | 20 | 40 | 60
    sampleRateHz?:         number;   // 8000 | 16000 | 32000 | 48000
    voxEnabled?:           boolean;
    voxThresholdRms?:      number;
    internalAudioEnabled?: boolean;
    internalAudioMixMic?:  boolean;
    customBitrate?:        number;   // 0 = use preset default
    opusComplexity?:       number;   // -1 = use preset default
    telemetryIntervalMs?:  number;
    streamMetricsEnabled?: boolean;
    jitterControlEnabled?: boolean;
    listenerRecordingEnabled?: boolean;
}

export interface Location {
    lat:      number | null;
    lng:      number | null;
    accuracy: number | null;
    altitude: number | null;
    speed:    number | null;
    bearing:  number | null;
    ts:       number | null;
}

export interface Channel {
    id:              string;
    displayName:     string;
    controlWs:       WebSocket | null;
    phoneWs:         WebSocket | null;
    browsers:        Set<WebSocket>;
    codecConfig:     CodecConfig | null;
    streamingActive: boolean;
    callActive:      boolean;
    cleanupTimer:    ReturnType<typeof setTimeout> | null;
    recording:       Recording;
    telemetry:       Telemetry;
    snapshotResolve: ((buf: Buffer) => void) | null;
    location:        Location;
    signalingPhone:    WebSocket | null;
    signalingBrowsers: Set<WebSocket>;
    stats:           ChannelStats;
    featureFlags:    FeatureFlags;
    /** OPT: last JSON sent via statsInterval — used to skip broadcast when unchanged. */
    lastStatsJson:   string;
    // ── Phase 3: Transport visibility ─────────────────────────────────────────
    /** "webrtc_p2p" | "websocket_relay" | "negotiating" | "fallback" | null */
    transportMode:   string | null;
    /** Round-trip time of the active transport in ms, as reported by the phone. */
    transportRttMs:  number | null;
    /** ICE connection state string from the phone's RTCPeerConnection. */
    iceState:        string | null;
    // ── Phase 6 Step 28: Silence notification protocol ─────────────────────────
    /**
     * Set to true when the phone sends { type:"silence", active:true } — meaning
     * AudioRecord was soft-suspended after ~5 s of sustained silence.
     * While true the statsInterval sends a lightweight { type:"silence" } frame to
     * browsers instead of the full ~200-byte stats payload, reducing data usage.
     */
    silenceActive:   boolean;
}

// ── Registry ──────────────────────────────────────────────────────────────────

export const streams = new Map<string, Channel>();

// ── API ───────────────────────────────────────────────────────────────────────

export function getOrCreateChannel(id: string, displayName?: string): Channel {
    if (!streams.has(id)) {
        streams.set(id, {
            id,
            displayName:     displayName || id.slice(0, 8),
            controlWs:       null,
            phoneWs:         null,
            browsers:        new Set(),
            codecConfig:     null,
            streamingActive: false,
            callActive:      false,
            cleanupTimer:    null,
            recording: {
                active: false, ffmpegProc: null,
                filePath: null, startedAt: null, frameHook: null,
            },
            telemetry: {
                batteryLevel: null, charging: false,
                signalDbm: null, cpuTempC: null,
                wifiSsid: null, updatedAt: null,
                voxDropRate: null, voxEnabled: null, voxThreshold: null,
                // Feature 9: new fields
                usedRamMB: null, totalRamMB: null, netType: null,
                linkSpeedMbps: null, screenOn: null,
                streamFps: null, streamKbps: null, streamUptimeSec: null,
            },
            snapshotResolve: null,
            location: {
                lat: null, lng: null, accuracy: null,
                altitude: null, speed: null, bearing: null, ts: null,
            },
            signalingPhone:    null,
            signalingBrowsers: new Set(),
            stats: {
                framesRelayed: 0, bytesRelayed: 0,
                connectedAt: null, disconnectedAt: null, lastActivityMs: 0,
            },
            featureFlags: {},
            lastStatsJson: '',
            // Phase 3: transport visibility — populated by transportStatus from phone
            transportMode:  null,
            transportRttMs: null,
            iceState:       null,
            // Phase 6 Step 28: silence state — populated by silence messages from phone
            silenceActive:  false,
        });
        logger.info('Channel created', { channelId: id.slice(0, 8), displayName: displayName || id.slice(0, 8) });
    }
    return streams.get(id)!;
}

export function scheduleGrace(id: string): void {
    const ch = streams.get(id);
    if (!ch) return;
    if (ch.cleanupTimer) clearTimeout(ch.cleanupTimer);
    logger.info('Channel entering grace period', {
        channelId: id.slice(0, 8), graceSec: config.CHANNEL_GRACE_MS / 1000,
    });
    ch.cleanupTimer = setTimeout(() => {
        const c = streams.get(id);
        if (c && !isPhoneConnected(c) && c.browsers.size === 0 && c.signalingBrowsers.size === 0) {
            // §5.3: Close any open signalingBrowsers sockets before eviction so their
            // close events don't fire after streams.delete() and re-insert a stale entry.
            for (const ws of c.signalingBrowsers) {
                if (ws.readyState === WebSocket.OPEN) {
                    try { ws.close(1000, 'Channel evicted'); } catch { /* ignore */ }
                }
            }
            c.signalingBrowsers.clear();
            streams.delete(id);
            logger.info('Channel evicted', { channelId: id.slice(0, 8) });
        }
    }, config.CHANNEL_GRACE_MS);
}

export function cancelGrace(id: string): void {
    const ch = streams.get(id);
    if (!ch) return;
    if (ch.cleanupTimer) { clearTimeout(ch.cleanupTimer); ch.cleanupTimer = null; }
}

export function broadcast(ch: Channel, msg: string | Buffer): void {
    for (const c of ch.browsers) {
        if (c.readyState === WebSocket.OPEN) {
            try { c.send(msg); } catch { ch.browsers.delete(c); }
        } else {
            ch.browsers.delete(c);
        }
    }
}

export function isPhoneConnected(ch: Channel): boolean {
    // Check readyState === OPEN for each socket, not just non-null.
    // A socket can be non-null but in CLOSING/CLOSED state after an abrupt disconnect,
    // which would falsely keep the channel alive and prevent grace-timer eviction.
    const open = (ws: WebSocket | null): boolean =>
        ws != null && ws.readyState === WebSocket.OPEN;
    // Include signalingPhone: a WebRTC-only device connects via /signal only
    // (no /stream or /control yet) and must not be treated as offline / evicted.
    return open(ch.controlWs) || open(ch.phoneWs) || open(ch.signalingPhone);
}
