/**
 * modules/camera/CameraModule.ts — Camera streaming (v5.2).
 * Routes: GET /camera-{front,back}-view  WS /camera-{front,back}  WS /camera-{front,back}-watch
 */
import path from 'path';
import fs   from 'fs';
import WebSocket from 'ws';
import type { IncomingMessage, ServerResponse } from 'http';

import { ServerModule }        from '../../core/ServerModule';
import { HttpRouteDefinition } from '../../core/HttpRouteDefinition';
import { WsRouteDefinition }   from '../../core/WsRouteDefinition';
import { HealthCheck }         from '../../core/HealthCheck';
import type { ServerContext }   from '../../core/ServerContext';
import { forModule } from '../../logger';

const log = forModule('Camera');

interface CameraChannel {
    senderWs:    WebSocket | null;
    viewers:     Set<WebSocket>;
    displayName: string;
}

const frontChannels = new Map<string, CameraChannel>();
const backChannels  = new Map<string, CameraChannel>();

function getMap(facing: 'front' | 'back') {
    return facing === 'front' ? frontChannels : backChannels;
}

function getOrCreate(map: Map<string, CameraChannel>, id: string, name?: string): CameraChannel {
    if (!map.has(id)) map.set(id, { senderWs: null, viewers: new Set(), displayName: name || id.slice(0, 8) });
    return map.get(id)!;
}

function cleanupIfEmpty(map: Map<string, CameraChannel>, id: string): void {
    const ch = map.get(id);
    if (ch && !ch.senderWs && ch.viewers.size === 0) {
        map.delete(id);
        log.info('Camera channel evicted', { channelId: id.slice(0, 8) });
    }
}

function serveHtml(htmlPath: string) {
    return (_req: IncomingMessage, res: ServerResponse) => {
        fs.readFile(htmlPath, (err, data) => {
            if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('Not found'); return; }
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            res.end(data);
        });
    };
}

function makeSenderHandler(facing: 'front' | 'back') {
    const map = getMap(facing);
    return (ws: WebSocket, req: IncomingMessage) => {
        const p   = new URL(req.url ?? '/', 'http://localhost').searchParams;
        const id  = p.get('id');
        const name = p.get('name') ?? '';
        if (!id) { ws.close(1008, 'Missing id'); return; }
        const ch = getOrCreate(map, id, name);
        if (ch.senderWs?.readyState === WebSocket.OPEN) {
            log.warn('Camera sender replaced', { facing, channelId: id.slice(0, 8) });
            ch.senderWs.close(1000, 'Replaced');
        }
        ch.senderWs = ws;
        log.info('Camera sender connected', { facing, channelId: id.slice(0, 8), name });
        ws.on('message', (data: Buffer, isBinary: boolean) => {
            if (!isBinary) return;
            for (const v of ch.viewers) {
                if (v.readyState === WebSocket.OPEN) { try { v.send(data, { binary: true }); } catch { /* ignore */ } }
                else ch.viewers.delete(v);
            }
        });
        ws.on('close', () => {
            if (ch.senderWs === ws) ch.senderWs = null;
            log.info('Camera sender disconnected', { facing, channelId: id.slice(0, 8) });
            const msg = JSON.stringify({ type: 'senderDisconnected' });
            for (const v of ch.viewers) { try { v.send(msg); } catch { /* ignore */ } }
            cleanupIfEmpty(map, id);
        });
        ws.on('error', (e: Error) => log.error('Camera sender error', { facing, channelId: id.slice(0, 8), error: e.message }));
    };
}

function makeViewerHandler(facing: 'front' | 'back') {
    const map = getMap(facing);
    return (ws: WebSocket, req: IncomingMessage) => {
        const id = new URL(req.url ?? '/', 'http://localhost').searchParams.get('id');
        if (!id) { ws.close(1008, 'Missing id'); return; }
        const ch = getOrCreate(map, id);
        ch.viewers.add(ws);
        log.info('Camera viewer connected', { facing, channelId: id.slice(0, 8), viewers: ch.viewers.size });
        try { ws.send(JSON.stringify({ type: 'senderStatus', connected: ch.senderWs?.readyState === WebSocket.OPEN })); } catch { /* ignore */ }
        ws.on('close', () => {
            ch.viewers.delete(ws);
            log.info('Camera viewer disconnected', { facing, channelId: id.slice(0, 8), viewers: ch.viewers.size });
            cleanupIfEmpty(map, id);
        });
        ws.on('error', (e: Error) => { log.error('Camera viewer error', { error: e.message }); ch.viewers.delete(ws); });
    };
}

export class CameraModule extends ServerModule {
    get name(): string { return 'Camera'; }
    register(ctx: ServerContext): void {
        // Use process.cwd() (always = server/ directory) so the path is correct
        // in BOTH ts-node dev mode (src/modules/camera) AND compiled mode (dist/src/modules/camera).
        // __dirname-relative paths with ../../.. land at dist/ in compiled mode, not server/.
        const htmlPath = path.join(process.cwd(), 'public', 'camera.html');
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/camera-front-view', serveHtml(htmlPath)));
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/camera-back-view',  serveHtml(htmlPath)));
        ctx.addWsRoute(new WsRouteDefinition('/camera-front',       makeSenderHandler('front')));
        ctx.addWsRoute(new WsRouteDefinition('/camera-back',        makeSenderHandler('back')));
        ctx.addWsRoute(new WsRouteDefinition('/camera-front-watch', makeViewerHandler('front')));
        ctx.addWsRoute(new WsRouteDefinition('/camera-back-watch',  makeViewerHandler('back')));
        ctx.addHealthCheck(new HealthCheck('camera-channels', async () => ({
            status: 'ok' as const,
            frontStreams: frontChannels.size, backStreams: backChannels.size,
            totalViewers: [...frontChannels.values(), ...backChannels.values()].reduce((s, c) => s + c.viewers.size, 0),
        })));
        log.info('CameraModule registered');
    }
}

export default CameraModule;
