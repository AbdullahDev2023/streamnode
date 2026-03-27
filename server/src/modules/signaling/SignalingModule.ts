/**
 * modules/signaling/SignalingModule.ts — WebRTC signaling relay (v5.2).
 */
import WebSocket from 'ws';
import type { IncomingMessage } from 'http';
import config  from '../../config';
import { forModule } from '../../logger';
import * as channels from '../../channels';
import { ServerModule } from '../../core/ServerModule';
import { HttpRouteDefinition } from '../../core/HttpRouteDefinition';
import { WsRouteDefinition }   from '../../core/WsRouteDefinition';
import { HealthCheck }         from '../../core/HealthCheck';
import type { ServerContext }   from '../../core/ServerContext';

const logger = forModule('Signaling');

/**
 * Build the ICE server list served to browsers via GET /ice-config.
 *
 * STUN-ONLY by design.  AuraCast never uses TURN:
 *   • ~80-85 % of networks: STUN succeeds → WebRTC P2P (⚡ SRTP/DTLS, lowest latency).
 *   • Remaining networks (symmetric NAT, corporate UDP block): ICE FAILED triggers the
 *     built-in WebSocket relay fallback in ConnectionOrchestrator (<50 ms switchover).
 *
 * The WS relay is an equivalent substitute for TURN at zero extra cost.
 * TURN env vars are rejected by config.ts at startup, so they can never appear here.
 */
function buildIceServers(): { urls: string }[] {
    const servers = config.STUN_SERVERS
        .split(',')
        .map(s => s.trim())
        .filter(s => s.startsWith('stun:') || s.startsWith('stuns:'))  // belt-and-suspenders
        .map(urls => ({ urls }));
    logger.info(`STUN-only ICE config ready — ${servers.length} server(s): ${servers.map(s => s.urls).join(', ')}`);
    return servers;
}

function wsOpen(ws: WebSocket | null): ws is WebSocket {
    return ws != null && ws.readyState === WebSocket.OPEN;
}
function safeSend(ws: WebSocket | null, obj: object): void {
    if (!wsOpen(ws)) return;
    try { ws.send(JSON.stringify(obj)); } catch { /* closing */ }
}

function handleSignal(ws: WebSocket, req: IncomingMessage): void {
    const u    = new URL(req.url ?? '/', 'http://localhost');
    const id   = u.searchParams.get('id');
    const role = u.searchParams.get('role');

    if (!id)                                    { ws.close(1008, 'Missing ?id='); return; }
    if (role !== 'phone' && role !== 'browser') { ws.close(1008, 'role must be phone or browser'); return; }

    const ch  = channels.getOrCreateChannel(id);
    const log = logger.child({ channelId: id.slice(0, 8), role });
    channels.cancelGrace(id);

    if (role === 'phone') {
        if (wsOpen(ch.signalingPhone)) {
            log.info('Replacing stale signalingPhone');
            ch.signalingPhone!.close(1000, 'Replaced');
        }
        ch.signalingPhone = ws;
        // Mark alive so the global server heartbeat (ws.ping every WS_PING_INTERVAL_MS)
        // does not terminate this socket. The global heartbeat in server.ts is the sole
        // keepalive mechanism — a redundant per-socket setInterval was removed (OPT: battery).
        (ws as WebSocket & { isAlive?: boolean }).isAlive = true;
        ws.on('pong', () => { (ws as WebSocket & { isAlive?: boolean }).isAlive = true; });
        log.info('Signaling phone connected', { browsers: ch.signalingBrowsers.size });
        safeSend(ws, { type: 'welcome', role: 'phone', channelId: id });
        for (const bws of ch.signalingBrowsers) safeSend(bws, { type: 'phone-ready' });

        ws.on('message', (data: Buffer | string) => {
            let parsed: object;
            try { parsed = JSON.parse(data.toString()) as object; } catch { return; }
            log.debug('Phone→browsers relay', { type: (parsed as {type:string}).type });
            for (const bws of ch.signalingBrowsers) safeSend(bws, parsed);
        });
        ws.on('close', (code: number) => {
            if (ch.signalingPhone !== ws) return;
            ch.signalingPhone = null;
            log.info('Signaling phone disconnected', { code });
            for (const bws of ch.signalingBrowsers) safeSend(bws, { type: 'peer-left', role: 'phone' });
            if (!channels.isPhoneConnected(ch)) channels.scheduleGrace(id);
        });
        ws.on('error', (e: Error) => { log.error('Phone signaling error', { error: e.message }); });
        return;
    }

    ch.signalingBrowsers.add(ws);
    // Mark alive for the same heartbeat reason as the phone side above.
    (ws as WebSocket & { isAlive?: boolean }).isAlive = true;
    ws.on('pong', () => { (ws as WebSocket & { isAlive?: boolean }).isAlive = true; });
    log.info('Signaling browser connected', { browsers: ch.signalingBrowsers.size });
    safeSend(ws, { type: 'welcome', role: 'browser', channelId: id });
    safeSend(ws, wsOpen(ch.signalingPhone) ? { type: 'phone-ready' } : { type: 'waiting' });

    ws.on('message', (data: Buffer | string) => {
        let parsed: object;
        try { parsed = JSON.parse(data.toString()) as object; } catch { return; }
        log.debug('Browser→phone relay', { type: (parsed as {type:string}).type });
        safeSend(ch.signalingPhone, parsed);
    });
    ws.on('close', () => {
        ch.signalingBrowsers.delete(ws);
        log.info('Signaling browser disconnected', { browsers: ch.signalingBrowsers.size });
        safeSend(ch.signalingPhone, { type: 'peer-left', role: 'browser' });
        if (!channels.isPhoneConnected(ch) && ch.signalingBrowsers.size === 0) channels.scheduleGrace(id);
    });
    ws.on('error', (e: Error) => log.error('Browser signaling error', { error: e.message }));
}

export class SignalingModule extends ServerModule {
    get name() { return 'Signaling'; }

    register(ctx: ServerContext): void {
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/ice-config', (_req, res) => {
            const iceServers = buildIceServers();
            res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
            res.end(JSON.stringify({ iceServers, stunOnly: true }));
        }));
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/webrtc-status', (_req, res) => {
            const out = [...channels.streams.entries()]
                .filter(([, ch]) => ch.signalingPhone || ch.signalingBrowsers.size > 0)
                .map(([id, ch]) => ({ id: id.slice(0,8), phoneSignaling: wsOpen(ch.signalingPhone), browserSignalers: ch.signalingBrowsers.size }));
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(out));
        }));
        ctx.addWsRoute(new WsRouteDefinition('/signal', (ws, req) => handleSignal(ws, req)));
        ctx.addHealthCheck(new HealthCheck('webrtc-signaling', async () => {
            const active = [...channels.streams.values()].filter(ch => wsOpen(ch.signalingPhone) || ch.signalingBrowsers.size > 0).length;
            return { status: 'ok', signalingActive: active, stunOnly: true, stunServers: config.STUN_SERVERS };
        }));
        logger.info('SignalingModule registered — STUN-only mode', { routes: '/ice-config /webrtc-status WS:/signal', stun: config.STUN_SERVERS });
    }
}

export default SignalingModule;
