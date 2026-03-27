/**
 * routes/websocket.ts — WebSocket handlers (v5.2).
 * /stream  — Android audio frames
 * /control — Android command channel (telemetry, location, snapshot)
 * /listen  — Browser audio consumer
 */
import WebSocket from 'ws';
import type { IncomingMessage } from 'http';
import config from '../config';
import { forModule } from '../logger';
import * as channels from '../channels';
import type { Channel, CodecConfig } from '../channels';
import { getMetricsModule } from '../modules/metrics/MetricsModule';

const logger = forModule('WS');

type LiveWs = WebSocket & { isAlive: boolean };

export function handler(ws: WebSocket, req: IncomingMessage): void {
    const u        = new URL(req.url ?? '/', 'http://localhost');
    const streamId = u.searchParams.get('id');
    const name     = u.searchParams.get('name') ?? '';

    if (!streamId) {
        logger.warn('WebSocket rejected — missing ?id=', { path: u.pathname });
        ws.close(1008, 'Missing ?id='); return;
    }

    (ws as LiveWs).isAlive = true;
    ws.on('pong', () => {
        (ws as LiveWs).isAlive = true;
        const ch = channels.streams.get(streamId);
        // Update lastActivityMs for both /stream (phoneWs) and /control (controlWs).
        // WebRTC-only devices connect via /control only — without this they are never
        // touched by the stale-socket check and get evicted while genuinely alive.
        if (ch && (ws === ch.phoneWs || ws === ch.controlWs)) {
            ch.stats.lastActivityMs = Date.now();
        }
    });

    if      (u.pathname === '/stream')  handleStream (ws, streamId, name);
    else if (u.pathname === '/control') handleControl(ws, streamId, name);
    else if (u.pathname === '/listen')  handleListen (ws, streamId);
    else {
        logger.warn('WebSocket rejected — unknown path', { path: u.pathname });
        ws.close(1008, 'Unknown path');
    }
}

function statusPayload(ch: Channel) {
    return JSON.stringify({
        type: 'status', phoneConnected: channels.isPhoneConnected(ch),
        streamingActive: ch.streamingActive, callActive: ch.callActive,
        volumeGain: config.SERVER_VOLUME_GAIN,
    });
}

// ── /stream ───────────────────────────────────────────────────────────────────

function handleStream(ws: WebSocket, streamId: string, name: string): void {
    const ch  = channels.getOrCreateChannel(streamId, name);
    const log = logger.child({ channelId: streamId.slice(0, 8) });
    channels.cancelGrace(streamId);

    if (ch.phoneWs?.readyState === WebSocket.OPEN) {
        log.info('Replacing stale phoneWs');
        ch.phoneWs.close(1000, 'Replaced');
    }
    ch.phoneWs = ws;
    // Bug #1 fix: guard connectedAt so a /stream reconnect does not overwrite the
    // uptime origin set earlier by /control on WebRTC-only devices. Only reset when
    // truly starting fresh (no prior connection exists).
    if (!ch.stats.connectedAt) {
        ch.stats.connectedAt = Date.now();
    }
    ch.stats.disconnectedAt = null;
    ch.stats.lastActivityMs = Date.now();
    ch.streamingActive = false;
    if (name) ch.displayName = name;

    log.info('Phone stream connected', { displayName: ch.displayName });
    channels.broadcast(ch, JSON.stringify({
        type: 'status', phoneConnected: true,
        streamingActive: false, volumeGain: config.SERVER_VOLUME_GAIN,
    }));

    ws.on('message', (data: Buffer | string, isBinary: boolean) => {
        ch.stats.lastActivityMs = Date.now();
        if (isBinary) {
            ch.stats.framesRelayed++;
            ch.stats.bytesRelayed += (data as Buffer).byteLength ?? 0;
            for (const c of ch.browsers) {
                if (c.readyState === WebSocket.OPEN) c.send(data, { binary: true });
            }
            ch.recording.frameHook?.(data as Buffer);
            return;
        }
        const text = data.toString();
        if (text === '{"type":"ping"}') {
            try { ws.send('{"type":"pong"}'); } catch { /* ignore */ }
            return;
        }
        try {
            const p = JSON.parse(text) as { type: string; active?: boolean };
            if (p.type === 'codec') {
                ch.codecConfig = {
                    codec:      (p as Record<string,unknown>).codec      as string  ?? 'opus',
                    sampleRate: Number((p as Record<string,unknown>).sampleRate)    || 48000,
                    channels:   Number((p as Record<string,unknown>).channels)      || 1,
                    bitrate:    (p as Record<string,unknown>).bitrate  != null
                                    ? Number((p as Record<string,unknown>).bitrate) : undefined,
                    frameMs:    (p as Record<string,unknown>).frameMs  != null
                                    ? Number((p as Record<string,unknown>).frameMs) : undefined,
                } satisfies CodecConfig;
                channels.broadcast(ch, text);
                log.debug('Codec config stored', { sampleRate: ch.codecConfig.sampleRate });
            } else if (p.type === 'streamingState') {
                ch.streamingActive = !!p.active;
                log.info('streamingState', { active: ch.streamingActive });
                channels.broadcast(ch, JSON.stringify({
                    type: 'status', phoneConnected: true,
                    streamingActive: ch.streamingActive, volumeGain: config.SERVER_VOLUME_GAIN,
                }));
            }
        } catch (e) {
            log.warn('Non-JSON text on /stream — forwarding', { error: (e as Error).message, preview: text.slice(0, 80) });
            channels.broadcast(ch, text);
        }
    });

    ws.on('close', (code: number) => {
        if (ch.phoneWs !== ws) return;
        ch.phoneWs = null;
        // Only clear streaming flags when no other phone path is alive.
        if (!channels.isPhoneConnected(ch)) {
            ch.streamingActive = false;
            ch.callActive = false;
        }
        ch.stats.disconnectedAt = Date.now();
        log.info('Phone stream disconnected', { code });
        // Bug #2 fix: use statusPayload() so callActive and volumeGain are included —
        // the old hand-rolled JSON was missing callActive, breaking the browser badge.
        channels.broadcast(ch, statusPayload(ch));
        if (!channels.isPhoneConnected(ch)) channels.scheduleGrace(streamId);
    });
    ws.on('error', (e: Error) => log.error('Phone stream error', { error: e.message }));
}

// ── /control ──────────────────────────────────────────────────────────────────

function handleControl(ws: WebSocket, streamId: string, name: string): void {
    const ch  = channels.getOrCreateChannel(streamId, name);
    const log = logger.child({ channelId: streamId.slice(0, 8) });
    channels.cancelGrace(streamId);

    if (ch.controlWs?.readyState === WebSocket.OPEN) {
        log.info('Replacing stale controlWs');
        ch.controlWs.close(1000, 'Replaced');
    }
    ch.controlWs = ws;
    if (name) ch.displayName = name;
    // Fix: set connectedAt so uptime is correct for WebRTC-only devices (no /stream socket).
    // Also always clear disconnectedAt on reconnect so the uptime counter resets correctly
    // instead of showing a stale disconnect timestamp from the previous session.
    if (!ch.stats.connectedAt) {
        ch.stats.connectedAt = Date.now();
    }
    ch.stats.disconnectedAt = null;
    ch.stats.lastActivityMs = Date.now();
    log.info('Control connected', { displayName: ch.displayName });

    try { ws.send(JSON.stringify({ type: 'welcome', status: 'ready' })); } catch { /* ignore */ }
    channels.broadcast(ch, statusPayload(ch));

    ws.on('message', (data: Buffer | string, isBinary: boolean) => {
        if (isBinary) {
            if (ch.snapshotResolve) {
                log.debug('Snapshot binary received', { bytes: (data as Buffer).byteLength });
                ch.snapshotResolve(data as Buffer);
            } else {
                log.warn('Binary on /control with no snapshot pending');
            }
            return;
        }
        // Fix: track last activity so the stale-socket check works for control-only paths.
        ch.stats.lastActivityMs = Date.now();
        let p: Record<string, unknown>;
        try { p = JSON.parse(data.toString()) as Record<string, unknown>; }
        catch (e) {
            log.warn('JSON parse error on /control — discarded', {
                error: (e as Error).message, preview: data.toString().slice(0, 120),
            });
            return;
        }

        if (p.type === 'ping') { try { ws.send('{"type":"pong"}'); } catch { /* */ } return; }

        // BUG FIX 4 — WebRTC-only devices have no /stream socket; they send their
        // codec announce on /control instead. Handle it here identically to the
        // /stream handler so late-joining browsers receive the seeded codec config
        // and the server stores sampleRate/bitrate/frameMs for dashboard display.
        if (p.type === 'codec') {
            ch.codecConfig = {
                codec:      (p as Record<string,unknown>).codec      as string  ?? 'opus',
                sampleRate: Number((p as Record<string,unknown>).sampleRate)    || 48000,
                channels:   Number((p as Record<string,unknown>).channels)      || 1,
                bitrate:    (p as Record<string,unknown>).bitrate  != null
                                ? Number((p as Record<string,unknown>).bitrate) : undefined,
                frameMs:    (p as Record<string,unknown>).frameMs  != null
                                ? Number((p as Record<string,unknown>).frameMs) : undefined,
            } satisfies CodecConfig;
            channels.broadcast(ch, JSON.stringify({ type: 'codec', ...ch.codecConfig }));
            log.debug('Codec config stored [control path]', { sampleRate: ch.codecConfig.sampleRate });
            return;
        }

        if (p.type === 'statusUpdate') {
            ch.streamingActive = p.status === 'STREAMING' || p.status === 'CALL_ACTIVE';
            ch.callActive      = p.status === 'CALL_ACTIVE';
            log.info('statusUpdate', { status: p.status, streaming: ch.streamingActive });
            channels.broadcast(ch, statusPayload(ch));
        }

        // WebRTC-only devices send streamingState on /control (no /stream socket).
        // Handle it here identically to the /stream handler so the dashboard updates.
        if (p.type === 'streamingState') {
            ch.streamingActive = !!p.active;
            log.info('streamingState [control]', { active: ch.streamingActive });
            channels.broadcast(ch, statusPayload(ch));
        }

        if (p.type === 'telemetry') {
            // Phase 2 OPT: Delta telemetry support.
            // When p.delta === true the phone sent only changed fields; we merge
            // them onto ch.telemetry so unchanged fields retain their last value.
            // When p.delta is absent or false this is a full resync — replace all.
            const isDelta = p.delta === true;

            if (isDelta) {
                // Selective merge — only overwrite fields present in this packet.
                if (typeof p.batteryLevel === 'number')  ch.telemetry.batteryLevel = p.batteryLevel;
                if (typeof p.charging     === 'boolean') ch.telemetry.charging     = p.charging;
                if (typeof p.signalDbm    === 'number')  ch.telemetry.signalDbm    = p.signalDbm;
                if (typeof p.cpuTempC     === 'number')  ch.telemetry.cpuTempC     = p.cpuTempC;
                if (typeof p.wifiSsid     === 'string')  ch.telemetry.wifiSsid     = p.wifiSsid;
                if (typeof p.usedRamMB    === 'number')  ch.telemetry.usedRamMB    = p.usedRamMB;
                if (typeof p.totalRamMB   === 'number')  ch.telemetry.totalRamMB   = p.totalRamMB;
                if (typeof p.netType      === 'string')  ch.telemetry.netType      = p.netType;
                if (typeof p.linkSpeedMbps === 'number') ch.telemetry.linkSpeedMbps = p.linkSpeedMbps;
                if (typeof p.screenOn     === 'boolean') ch.telemetry.screenOn     = p.screenOn;
                if (typeof p.streamFps    === 'number')  ch.telemetry.streamFps    = p.streamFps;
                if (typeof p.streamKbps   === 'number')  ch.telemetry.streamKbps   = p.streamKbps;
                if (typeof p.streamUptimeSec === 'number') ch.telemetry.streamUptimeSec = p.streamUptimeSec;
                if (typeof p.voxDropRate  === 'number')  ch.telemetry.voxDropRate  = p.voxDropRate;
                if (typeof p.voxEnabled   === 'boolean') ch.telemetry.voxEnabled   = p.voxEnabled;
                if (typeof p.voxThreshold === 'number')  ch.telemetry.voxThreshold = p.voxThreshold;
                ch.telemetry.updatedAt = Date.now();
            } else {
                // Full resync — replace entire telemetry object.
                ch.telemetry = {
                    batteryLevel: typeof p.batteryLevel === 'number'  ? p.batteryLevel : null,
                    charging:     typeof p.charging     === 'boolean' ? p.charging     : false,
                    signalDbm:    typeof p.signalDbm    === 'number'  ? p.signalDbm    : null,
                    cpuTempC:     typeof p.cpuTempC     === 'number'  ? p.cpuTempC     : null,
                    wifiSsid:     typeof p.wifiSsid     === 'string'  ? p.wifiSsid     : null,
                    updatedAt:    Date.now(),
                    voxDropRate:  typeof p.voxDropRate  === 'number'  ? p.voxDropRate  : null,
                    voxEnabled:   typeof p.voxEnabled   === 'boolean' ? p.voxEnabled   : null,
                    voxThreshold: typeof p.voxThreshold === 'number'  ? p.voxThreshold : null,
                    usedRamMB:       typeof p.usedRamMB       === 'number'  ? p.usedRamMB       : null,
                    totalRamMB:      typeof p.totalRamMB      === 'number'  ? p.totalRamMB      : null,
                    netType:         typeof p.netType         === 'string'  ? p.netType         : null,
                    linkSpeedMbps:   typeof p.linkSpeedMbps   === 'number'  ? p.linkSpeedMbps   : null,
                    screenOn:        typeof p.screenOn        === 'boolean' ? p.screenOn        : null,
                    streamFps:       typeof p.streamFps       === 'number'  ? p.streamFps       : null,
                    streamKbps:      typeof p.streamKbps      === 'number'  ? p.streamKbps      : null,
                    streamUptimeSec: typeof p.streamUptimeSec === 'number'  ? p.streamUptimeSec : null,
                };
            }
            channels.broadcast(ch, JSON.stringify({ type: 'telemetry', id: streamId, ...ch.telemetry }));
            // Feature 9: feed ring-buffer for MetricsModule history / SSE
            getMetricsModule()?.recordTelemetry(streamId, ch.telemetry);
            log.debug('Telemetry stored', { battery: ch.telemetry.batteryLevel, cpu: ch.telemetry.cpuTempC });
            const bat = ch.telemetry.batteryLevel;
            if (typeof bat === 'number') {
                const level = bat < 10 ? 'critical' : bat < 20 ? 'warn' : null;
                if (level) {
                    log.warn('Low battery alert', { battery: bat, level });
                    channels.broadcast(ch, JSON.stringify({ type: 'alert', level, id: streamId, message: `Battery ${level === 'critical' ? 'critically low' : 'low'}: ${bat}%` }));
                }
            }
        }

        // ── Phase 3: Transport status report from phone ───────────────────────────
        // Phone sends this after every ICE state change and on transport fallback events.
        // Stored on the channel and broadcast to all /listen browsers so the dashboard
        // can update the transport badge in real time.
        if (p.type === 'transportStatus') {
            ch.transportMode  = typeof p.mode     === 'string' ? p.mode     : null;
            ch.transportRttMs = typeof p.rtt      === 'number' ? p.rtt      : null;
            ch.iceState       = typeof p.iceState === 'string' ? p.iceState : null;
            channels.broadcast(ch, JSON.stringify({
                type: 'transportStatus', id: streamId,
                mode: ch.transportMode, rtt: ch.transportRttMs, iceState: ch.iceState,
            }));
            log.info('Transport status updated', {
                mode: ch.transportMode, rtt: ch.transportRttMs, iceState: ch.iceState,
            });
        }

        // ── Phase 6 Step 28: Silence notification protocol ───────────────────────
        // Phone sends { type:"silence", active:true/false } when AudioRecord is
        // soft-suspended (sustained VOX silence) or resumed (RMS spike detected).
        // We store the state on the channel so the statsInterval can send a
        // lightweight payload to browsers while the device is silent, and we
        // broadcast immediately so the dashboard badge updates in real time.
        if (p.type === 'silence') {
            ch.silenceActive = p.active === true;
            channels.broadcast(ch, JSON.stringify({
                type: 'silence', id: streamId, active: ch.silenceActive,
            }));
            log.debug('Silence state updated', { active: ch.silenceActive });
        }

        if (p.type === 'location') {            // FIX: use typeof guards — `?? null` would convert the valid value 0.0 (e.g. lat=0
            // on the equator, lng=0 at Greenwich) to null, breaking the dashboard map.
            ch.location = {
                lat:      typeof p.lat      === 'number' ? p.lat      : null,
                lng:      typeof p.lng      === 'number' ? p.lng      : null,
                accuracy: typeof p.accuracy === 'number' ? p.accuracy : null,
                altitude: typeof p.altitude === 'number' ? p.altitude : null,
                speed:    typeof p.speed    === 'number' ? p.speed    : null,
                bearing:  typeof p.bearing  === 'number' ? p.bearing  : null,
                ts:       typeof p.ts       === 'number' ? p.ts       : null,
            };
            channels.broadcast(ch, JSON.stringify({ type: 'location', id: streamId, ...ch.location }));
            log.debug('Location stored', { lat: ch.location.lat, lng: ch.location.lng });
        }
    });

    ws.on('close', (code: number) => {
        if (ch.controlWs !== ws) return;
        ch.controlWs = null;
        // Only clear streamingActive if no other phone path is still open.
        // A WebRTC-only device keeps streaming through signalingPhone even
        // if /control disconnects temporarily.
        if (!channels.isPhoneConnected(ch)) {
            ch.streamingActive = false;
            ch.callActive = false;
        }
        log.info('Control disconnected', { code });
        // Bug #2 fix: use statusPayload() so callActive and volumeGain are included.
        channels.broadcast(ch, statusPayload(ch));
        if (!channels.isPhoneConnected(ch)) channels.scheduleGrace(streamId);
    });
    ws.on('error', (e: Error) => log.error('Control socket error', { error: e.message }));
}

// ── /listen ───────────────────────────────────────────────────────────────────

function handleListen(ws: WebSocket, streamId: string): void {
    const ch  = channels.getOrCreateChannel(streamId);
    const log = logger.child({ channelId: streamId.slice(0, 8) });
    channels.cancelGrace(streamId);
    ch.browsers.add(ws);
    log.info('Browser joined', { listeners: ch.browsers.size });

    // Send current status immediately so the dashboard doesn't wait for the next broadcast.
    ws.send(statusPayload(ch));
    // Seed codec config whether it arrived on /stream (legacy WS) or /control (WebRTC path).
    // After BUG FIX 4, codec announcements from WebRTC-only devices are also stored on
    // ch.codecConfig, so this seed is now correct for both transport paths.
    if (ch.codecConfig) ws.send(JSON.stringify({ type: 'codec', ...ch.codecConfig }));

    // Seed the newly-joined browser with the latest telemetry and location
    // so it shows data immediately without waiting for the next 60-second push.
    if (ch.telemetry?.updatedAt != null) {
        try { ws.send(JSON.stringify({ type: 'telemetry', id: streamId, ...ch.telemetry })); } catch { /* ignore */ }
    }
    if (ch.location?.ts != null) {
        try { ws.send(JSON.stringify({ type: 'location', id: streamId, ...ch.location })); } catch { /* ignore */ }
    }
    // BUG FIX 1 — Seed transportStatus so browser transport badge is correct on join.
    // Without this, a browser opening /player?id=... after WebRTC P2P is already
    // established shows "Connecting…" indefinitely because transportStatus is only
    // pushed on ICE state changes — not on every new /listen connection.
    if (ch.transportMode != null) {
        try {
            ws.send(JSON.stringify({
                type: 'transportStatus', id: streamId,
                mode: ch.transportMode, rtt: ch.transportRttMs, iceState: ch.iceState,
            }));
        } catch { /* ignore */ }
    }
    // BUG FIX 2 — Seed silence state so browser shows the correct badge immediately
    // when the phone is in VOX-gated silence at the time the browser joins.
    // Without this the statsInterval sends a lightweight silence payload up to 3 s
    // later, leaving the browser in an incorrect "active" state during that window.
    if (ch.silenceActive) {
        try { ws.send(JSON.stringify({ type: 'silence', id: streamId, active: true })); } catch { /* ignore */ }
    }

    ws.on('close', () => {
        ch.browsers.delete(ws);
        log.info('Browser left', { listeners: ch.browsers.size });
        if (!channels.isPhoneConnected(ch)) channels.scheduleGrace(streamId);
    });
    ws.on('error', (e: Error) => log.error('Browser socket error', { error: e.message }));
}
