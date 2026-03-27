/**
 * modules/admin/AdminModule.ts — Device Admin HTTP API (v5.2).
 *
 *

 * GET  /admin/devices                All channels + telemetry
 * GET  /admin/:id/status             Firebase RTDB adminStatus
 * POST /admin/:id/lock|reboot|wipe   Device control
 * POST /admin/:id/camera             Toggle camera (?enable=true|false)
 * POST /admin/:id/brightness|volume  Hardware controls
 * POST /admin/:id/password           Reset device password
 * POST /admin/:id/clear-app          Clear app data (?package=…)
 * POST /admin/:id/uninstall          Uninstall package (?package=…)
 * POST /admin/:id/max-fails          Max failed-password wipes
 * POST /admin/:id/set-quality        Audio quality (HIGH_QUALITY|MEDIUM|LOW)
 * POST /admin/:id/set-vox            VOX silence gate (?enabled=bool&threshold=num)
 * POST /admin/:id/set-sample-rate    Audio sample rate (8000|16000|32000|48000)
 * POST /admin/:id/set-vbr            VBR toggle (?enabled=true|false)       [Feature 1]
 * POST /admin/:id/set-frame-ms       Opus frame size (?ms=2|5|10|20|40|60)  [Feature 1]
 * POST /admin/:id/set-internal-audio  Internal audio capture (API 29+)    [Feature 4]
 * POST /admin/:id/set-audio-config   Full custom audio parameter set       [Feature 8]
 * POST /admin/:id/feature-flags      Unified feature flag toggle (all features) [v6]
 * POST /admin/:id/snapshot           Capture JPEG from phone (5 s timeout)
 * POST /admin/:id/torch              Flashlight (?on=true|false)
 * POST /admin/:id/command            Generic admin_* passthrough
 */
import crypto    from 'crypto';
import WebSocket from 'ws';
import type { IncomingMessage, ServerResponse } from 'http';

import { ServerModule }        from '../../core/ServerModule';
import { HttpRouteDefinition } from '../../core/HttpRouteDefinition';
import type { ServerContext }   from '../../core/ServerContext';

import * as channels from '../../channels';
import type { FeatureFlags } from '../../channels';
import * as firebase from '../../firebase';
import { adminAuth }   from '../../middleware/adminAuth';
import { applyCors, handlePreflight } from '../../middleware/cors';
import { attachRequestId } from '../../middleware/requestId';
import { forModule } from '../../logger';

const logger = forModule('Admin');

// ── Types ─────────────────────────────────────────────────────────────────────

interface DispatchResult {
    ok:        boolean;
    commandId: string;
    channel:   'websocket' | 'firebase';
    action:    string;
    payload:   string;
    note?:     string;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function json(res: ServerResponse, status: number, body: object): void {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
}

function extractId(pathname: string): string | null {
    const parts = pathname.split('/').filter(Boolean);
    return parts.length >= 2 ? parts[1] : null;
}

function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
        let raw = '';
        req.on('data', (chunk: string) => {
            raw += chunk;
            if (raw.length > 8192) reject(new Error('request body too large'));
        });
        req.on('end', () => {
            try { resolve(raw ? JSON.parse(raw) as Record<string, unknown> : {}); }
            catch  { resolve({}); }
        });
        req.on('error', reject);
    });
}

async function dispatchAdminCommand(
    deviceId: string,
    action:   string,
    payload   = '',
): Promise<DispatchResult> {
    const ch        = channels.streams.get(deviceId);
    const commandId = crypto.randomUUID();
    const log       = logger.child({ deviceId: deviceId.slice(0, 8), action });

    if (ch && ch.controlWs && ch.controlWs.readyState === WebSocket.OPEN) {
        try {
            ch.controlWs.send(JSON.stringify({
                type: 'cmd', commandId, action, url: payload, source: 'admin-websocket',
            }));
            log.info('Command dispatched via WebSocket');
            return { ok: true, commandId, channel: 'websocket', action, payload };
        } catch (e: unknown) {
            log.warn('WebSocket dispatch failed — falling back to Firebase', { error: (e as Error).message });
        }
    }

    const { adminDb } = firebase;
    if (adminDb) {
        try {
            await adminDb.ref(`/users/${deviceId}/control`).set({
                commandId, command: action, url: payload,
                ts: Date.now(), source: 'admin-firebase',
                processed: false, processedAt: null,
            });
            log.info('Command queued in Firebase RTDB');
            return { ok: true, commandId, channel: 'firebase', action, payload, note: 'phone offline — queued in Firebase RTDB' };
        } catch (e: unknown) {
            log.error('Firebase admin write failed', { error: (e as Error).message });
            throw new Error(`Firebase write failed: ${(e as Error).message}`);
        }
    }

    throw new Error('phone offline and Firebase Admin not configured');
}

async function handleAdminRequest(req: IncomingMessage, res: ServerResponse): Promise<void> {
    attachRequestId(req, res);
    applyCors(res);
    if (handlePreflight(req, res)) return;
    if (!adminAuth(req, res)) return;

    const u        = new URL(req.url ?? '/', 'http://localhost');
    const pathname = u.pathname;
    const method   = req.method?.toUpperCase() ?? 'GET';

    // GET /admin/devices
    if (pathname === '/admin/devices' && method === 'GET') {
        return json(res, 200, [...channels.streams.values()].map(ch => ({
            id:              ch.id,
            displayName:     ch.displayName,
            phoneConnected:  channels.isPhoneConnected(ch),
            streamingActive: ch.streamingActive,
            listeners:       ch.browsers.size,
            telemetry:       ch.telemetry,
            location:        ch.location,
            // Feature 9C: include stats, codec config, and feature flags for dashboard metrics
            stats:           ch.stats,
            codecConfig:     ch.codecConfig,
            featureFlags:    ch.featureFlags,
            // WebRTC session status — true when phone is connected via /signal only
            signalingActive: ch.signalingPhone != null && ch.signalingPhone.readyState === WebSocket.OPEN,
            // Phase 3: transport visibility — populated from transportStatus messages
            transportMode:   ch.transportMode,
            transportRttMs:  ch.transportRttMs,
            iceState:        ch.iceState,
            // BUG FIX 8 — include silenceActive so admin panel can render silence badge
            // on initial devices fetch before any WS push arrives.
            silenceActive:   ch.silenceActive,
        })));
    }

    const deviceId = extractId(pathname);
    if (!deviceId) return json(res, 400, { error: 'missing device id in path' });

    const action = pathname.split('/').filter(Boolean).pop() ?? '';

    // GET /admin/:id/status
    if (action === 'status' && method === 'GET') {
        const { adminDb } = firebase;
        if (!adminDb) return json(res, 503, { error: 'Firebase Admin not configured' });
        try {
            const snap = await adminDb.ref(`/users/${deviceId}/adminStatus`).once('value');
            return json(res, 200, snap.val() || { isDeviceAdmin: false, isDeviceOwner: false });
        } catch (e: unknown) { return json(res, 500, { error: (e as Error).message }); }
    }

    if (method !== 'POST') return json(res, 405, { error: 'Method not allowed' });
    const body = await readBody(req);

    try {
        let result: DispatchResult;
        switch (action) {
            case 'lock':    result = await dispatchAdminCommand(deviceId, 'admin_lock');   break;
            case 'reboot':  result = await dispatchAdminCommand(deviceId, 'admin_reboot'); break;
            case 'wipe': {
                const confirm = (u.searchParams.get('confirm') ?? body['confirm'] ?? '') as string;
                if (confirm !== 'WIPE') return json(res, 400, { error: 'Wipe requires ?confirm=WIPE' });
                result = await dispatchAdminCommand(deviceId, 'admin_wipe');
                break;
            }
            case 'camera': {
                const enable = String(u.searchParams.get('enable') ?? body['enable'] ?? 'true');
                result = await dispatchAdminCommand(deviceId, 'admin_camera_disable', enable === 'false' ? 'true' : 'false');
                break;
            }
            case 'brightness':
                result = await dispatchAdminCommand(deviceId, 'admin_brightness', String(u.searchParams.get('value') ?? body['value'] ?? '128'));
                break;
            case 'volume':
                result = await dispatchAdminCommand(deviceId, 'admin_volume', String(u.searchParams.get('value') ?? body['value'] ?? '10'));
                break;
            case 'password': {
                const pw = (u.searchParams.get('password') ?? body['password'] ?? '') as string;
                if (!pw) return json(res, 400, { error: 'missing ?password=' });
                result = await dispatchAdminCommand(deviceId, 'admin_reset_password', pw);
                break;
            }
            case 'clear-app': {
                const pkg = (u.searchParams.get('package') ?? body['package'] ?? '') as string;
                if (!pkg) return json(res, 400, { error: 'missing ?package=' });
                result = await dispatchAdminCommand(deviceId, 'admin_clear_app_data', pkg);
                break;
            }
            case 'uninstall': {
                const pkg = (u.searchParams.get('package') ?? body['package'] ?? '') as string;
                if (!pkg) return json(res, 400, { error: 'missing ?package=' });
                result = await dispatchAdminCommand(deviceId, 'admin_uninstall_app', pkg);
                break;
            }
            case 'max-fails':
                result = await dispatchAdminCommand(deviceId, 'admin_max_fails', String(u.searchParams.get('value') ?? body['value'] ?? '10'));
                break;
            case 'set-quality': {
                const VALID = new Set(['HIGH_QUALITY', 'MEDIUM', 'LOW']);
                const level = ((u.searchParams.get('level') ?? body['level'] ?? 'HIGH_QUALITY') as string).toUpperCase().trim();
                if (!VALID.has(level)) return json(res, 400, { error: 'level must be HIGH_QUALITY|MEDIUM|LOW', received: level });
                result = await dispatchAdminCommand(deviceId, 'set_quality', level);
                break;
            }
            // ── Feature 3: VOX / Silence Gate ────────────────────────────────────
            // POST /admin/:id/set-vox?enabled=true&threshold=150
            // Body (alternative): { "enabled": true, "threshold": 150 }
            case 'set-vox': {
                const enabled   = String(u.searchParams.get('enabled')   ?? body['enabled']   ?? 'false').toLowerCase() !== 'false';
                const threshold = Number(u.searchParams.get('threshold') ?? body['threshold'] ?? 150);
                if (isNaN(threshold) || threshold < 0) {
                    return json(res, 400, { error: 'threshold must be a non-negative number' });
                }
                const payload = JSON.stringify({ enabled, threshold });
                result = await dispatchAdminCommand(deviceId, 'set_vox', payload);
                break;
            }
            // Feature 2: Sample rate selector
            // POST /admin/:id/set-sample-rate?rate=16000
            case 'set-sample-rate': {
                const VALID_RATES = new Set([8000, 16000, 32000, 48000]);
                const rate = parseInt((u.searchParams.get('rate') ?? String(body['rate'] ?? '48000')), 10);
                if (!VALID_RATES.has(rate)) {
                    return json(res, 400, { error: 'rate must be 8000|16000|32000|48000', received: rate });
                }
                result = await dispatchAdminCommand(deviceId, 'set_sample_rate', String(rate));
                break;
            }
            // ── Feature 1: VBR toggle ─────────────────────────────────────────
            // POST /admin/:id/set-vbr?enabled=true|false
            case 'set-vbr': {
                const enabled = String(u.searchParams.get('enabled') ?? body['enabled'] ?? 'true').toLowerCase();
                if (enabled !== 'true' && enabled !== 'false')
                    return json(res, 400, { error: 'enabled must be true|false' });
                result = await dispatchAdminCommand(deviceId, 'set_vbr', enabled);
                break;
            }
            // ── Feature 1: Frame size ─────────────────────────────────────────
            // POST /admin/:id/set-frame-ms?ms=20
            case 'set-frame-ms': {
                const VALID_MS = new Set([2, 5, 10, 20, 40, 60]);
                const ms = parseInt(String(u.searchParams.get('ms') ?? body['ms'] ?? '20'), 10);
                if (!VALID_MS.has(ms))
                    return json(res, 400, { error: 'ms must be 2|5|10|20|40|60', received: ms });
                result = await dispatchAdminCommand(deviceId, 'set_frame_ms', String(ms));
                break;
            }
            // ── Feature 4: Internal Audio / Media Capture ─────────────────────
            // POST /admin/:id/set-internal-audio
            // Body (or query params): { "enabled": bool, "mixWithMic": bool }
            case 'set-internal-audio': {
                const iaEnabled    = String(u.searchParams.get('enabled')    ?? body['enabled']    ?? 'false').toLowerCase() !== 'false';
                const mixWithMic   = String(u.searchParams.get('mixWithMic') ?? body['mixWithMic'] ?? 'true').toLowerCase()  !== 'false';
                const iaPayload    = JSON.stringify({ enabled: iaEnabled, mixWithMic });
                result = await dispatchAdminCommand(deviceId, 'internal_audio', iaPayload);
                break;
            }
            // ── Feature 8: Advanced Audio Config ─────────────────────────────
            // POST /admin/:id/set-audio-config
            // Body: { sampleRate, bitrate, frameMs, vbr, complexity, agc, ns, aec, voxThreshold }
            case 'set-audio-config': {
                const VALID_SR  = new Set([8000, 16000, 32000, 48000]);
                const VALID_FMS = new Set([2, 5, 10, 20, 40, 60]);

                const sampleRate = parseInt(String(body['sampleRate']   ?? 48000), 10);
                const bitrate    = parseInt(String(body['bitrate']      ?? 192000), 10);
                const frameMs    = parseInt(String(body['frameMs']      ?? 60), 10);
                const vbr        = body['vbr']  === true || body['vbr']  === 'true';
                const complexity = parseInt(String(body['complexity']   ?? 9), 10);
                const agc        = body['agc']  === true || body['agc']  === 'true';
                const ns         = body['ns']   === true || body['ns']   === 'true';
                const aec        = body['aec']  === true || body['aec']  === 'true';
                const voxThreshold = parseFloat(String(body['voxThreshold'] ?? 0));

                if (!VALID_SR.has(sampleRate))
                    return json(res, 400, { error: 'sampleRate must be 8000|16000|32000|48000', received: sampleRate });
                if (!VALID_FMS.has(frameMs))
                    return json(res, 400, { error: 'frameMs must be 2|5|10|20|40|60', received: frameMs });
                if (isNaN(bitrate) || bitrate < 6000 || bitrate > 510000)
                    return json(res, 400, { error: 'bitrate must be 6000–510000 bps', received: bitrate });
                if (isNaN(complexity) || complexity < 0 || complexity > 10)
                    return json(res, 400, { error: 'complexity must be 0–10', received: complexity });

                const cfg = JSON.stringify({ sampleRate, bitrate, frameMs, vbr, complexity, agc, ns, aec, voxThreshold });
                result = await dispatchAdminCommand(deviceId, 'set_audio_config', cfg);
                break;
            }
            // ── v6 FeatureFlags — unified toggle endpoint ─────────────────────
            // POST /admin/:id/feature-flags
            // Body: any subset of the FeatureFlags fields. Stored on the channel
            // and forwarded to the phone as action="feature_flags" JSON payload.
            // Browser /listen clients also receive the flags so player.html can
            // show/hide jitter slider and Record button based on server state.
            case 'feature-flags': {
                const flags: FeatureFlags = {};

                if (body['vbrEnabled']            !== undefined) flags.vbrEnabled            = body['vbrEnabled']            === true || body['vbrEnabled']            === 'true';
                if (body['frameSizeMs']            !== undefined) flags.frameSizeMs            = parseInt(String(body['frameSizeMs']),            10);
                if (body['sampleRateHz']           !== undefined) flags.sampleRateHz           = parseInt(String(body['sampleRateHz']),           10);
                if (body['voxEnabled']             !== undefined) flags.voxEnabled             = body['voxEnabled']             === true || body['voxEnabled']             === 'true';
                if (body['voxThresholdRms']        !== undefined) flags.voxThresholdRms        = parseFloat(String(body['voxThresholdRms']));
                if (body['internalAudioEnabled']   !== undefined) flags.internalAudioEnabled   = body['internalAudioEnabled']   === true || body['internalAudioEnabled']   === 'true';
                if (body['internalAudioMixMic']    !== undefined) flags.internalAudioMixMic    = body['internalAudioMixMic']    === true || body['internalAudioMixMic']    === 'true';
                if (body['customBitrate']          !== undefined) flags.customBitrate          = parseInt(String(body['customBitrate']),          10);
                if (body['opusComplexity']         !== undefined) flags.opusComplexity         = parseInt(String(body['opusComplexity']),         10);
                if (body['telemetryIntervalMs']    !== undefined) flags.telemetryIntervalMs    = parseInt(String(body['telemetryIntervalMs']),    10);
                if (body['streamMetricsEnabled']   !== undefined) flags.streamMetricsEnabled   = body['streamMetricsEnabled']   === true || body['streamMetricsEnabled']   === 'true';
                if (body['jitterControlEnabled']   !== undefined) flags.jitterControlEnabled   = body['jitterControlEnabled']   === true || body['jitterControlEnabled']   === 'true';
                if (body['listenerRecordingEnabled'] !== undefined) flags.listenerRecordingEnabled = body['listenerRecordingEnabled'] === true || body['listenerRecordingEnabled'] === 'true';

                // Persist flags on the channel so GET /admin/devices reflects current state
                const ch = channels.streams.get(deviceId);
                if (ch) {
                    ch.featureFlags = { ...ch.featureFlags, ...flags };
                    // Broadcast to all browser /listen clients so player.html reacts immediately
                    channels.broadcast(ch, JSON.stringify({ type: 'feature_flags', id: deviceId, ...ch.featureFlags }));
                }

                // Forward to the phone via the control WebSocket
                result = await dispatchAdminCommand(deviceId, 'feature_flags', JSON.stringify(flags));
                break;
            }
            case 'torch': {
                const onParam = (u.searchParams.get('on') ?? body['on'] ?? 'true') as string;
                result = await dispatchAdminCommand(deviceId, 'admin_torch', String(onParam).toLowerCase() !== 'false' ? 'true' : 'false');
                break;
            }
            case 'command': {
                const adminAction = (body['action'] ?? u.searchParams.get('action') ?? '') as string;
                const payload     = (body['payload'] ?? u.searchParams.get('payload') ?? '') as string;
                if (!adminAction) return json(res, 400, { error: 'missing "action" in body' });
                if (!adminAction.startsWith('admin_')) return json(res, 400, { error: 'action must start with admin_' });
                result = await dispatchAdminCommand(deviceId, adminAction, payload);
                break;
            }
            // ── Phase 3: Transport mode selector ─────────────────────────────────
            // POST /admin/:id/set-transport?mode=webrtc|websocket|auto
            // Dispatches set_transport_webrtc | set_transport_ws | set_transport_auto
            // to the phone, which calls ConnectionOrchestrator.setTransportMode().
            case 'set-transport': {
                const mode = (u.searchParams.get('mode') ?? body['mode'] ?? 'auto') as string;
                const VALID_MODES = new Set(['webrtc', 'websocket', 'auto']);
                if (!VALID_MODES.has(mode))
                    return json(res, 400, { error: 'mode must be webrtc|websocket|auto', received: mode });
                const action_name = mode === 'websocket' ? 'set_transport_ws'
                                  : mode === 'webrtc'    ? 'set_transport_webrtc'
                                  :                        'set_transport_auto';
                result = await dispatchAdminCommand(deviceId, action_name, mode);
                break;
            }
            case 'snapshot': return handleSnapshot(req, res, deviceId);
            default: return json(res, 404, { error: `unknown admin action: "${action}"` });
        }
        return json(res, result.channel === 'firebase' ? 202 : 200, result);
    } catch (e: unknown) {
        logger.error('Admin command failed', { deviceId: deviceId.slice(0, 8), action, error: (e as Error).message });
        return json(res, 409, { error: (e as Error).message });
    }
}

async function handleSnapshot(_req: IncomingMessage, res: ServerResponse, deviceId: string): Promise<void> {
    const ch  = channels.streams.get(deviceId);
    const log = logger.child({ deviceId: deviceId.slice(0, 8) });

    if (!ch)                                                   return json(res, 404, { error: 'device not found' });
    if (!ch.controlWs || ch.controlWs.readyState !== WebSocket.OPEN) return json(res, 409, { error: 'phone control channel not connected' });
    if (ch.snapshotResolve)                                    return json(res, 409, { error: 'snapshot already in progress' });

    try {
        const jpegBuf = await new Promise<Buffer>((resolve, reject) => {
            const timer = setTimeout(() => {
                if (ch.snapshotResolve === resolve) ch.snapshotResolve = null;
                reject(new Error('snapshot timeout (5 s) — phone did not respond'));
            }, 5000);
            ch.snapshotResolve = (buf: Buffer) => {
                clearTimeout(timer);
                ch.snapshotResolve = null;
                resolve(buf);
            };
            try {
                ch.controlWs!.send(JSON.stringify({
                    type: 'cmd', commandId: crypto.randomUUID(),
                    action: 'snapshot', url: '', source: 'admin-websocket',
                }));
                log.info('Snapshot command sent to phone');
            } catch (e: unknown) {
                clearTimeout(timer);
                ch.snapshotResolve = null;
                reject(new Error(`failed to send snapshot command: ${(e as Error).message}`));
            }
        });
        res.writeHead(200, {
            'Content-Type':        'image/jpeg',
            'Content-Disposition': `inline; filename="snap_${deviceId.slice(0, 8)}_${Date.now()}.jpg"`,
            'Content-Length':      jpegBuf.byteLength,
        });
        res.end(jpegBuf);
        log.info('Snapshot delivered', { bytes: jpegBuf.byteLength });
    } catch (e: unknown) {
        log.error('Snapshot failed', { error: (e as Error).message });
        return json(res, 409, { error: (e as Error).message });
    }
}

// ── Module registration ───────────────────────────────────────────────────────

export class AdminModule extends ServerModule {
    get name(): string { return 'Admin'; }

    register(ctx: ServerContext): void {
        // Negative-lookahead excludes /admin/:id/record/* paths so that RecordingModule
        // (loaded after AdminModule alphabetically) can match those routes first.
        ctx.addHttpRoute(new HttpRouteDefinition('*', /^\/admin(?!\/[^/]+\/record\/)(\/|$)/, handleAdminRequest));
        logger.info('AdminModule registered', {
            routes: '/admin/devices  /admin/:id/{lock,wipe,reboot,camera,brightness,volume,password,' +
                    'clear-app,uninstall,max-fails,set-quality,set-sample-rate,snapshot,torch,command,status}',
        });
    }
}

export default AdminModule;
