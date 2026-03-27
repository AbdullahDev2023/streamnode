/**
 * modules/screen/ScreenModule.ts — Screen share relay (v5.2).
 * Routes: GET /screen-view  WS /screen  WS /screen-watch
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

const log = forModule('Screen');

interface ScreenChannel {
    senderWs:    WebSocket | null;
    viewers:     Set<WebSocket>;
    displayName: string;
}

const screenChannels = new Map<string, ScreenChannel>();

function getOrCreate(id: string, name?: string): ScreenChannel {
    if (!screenChannels.has(id))
        screenChannels.set(id, { senderWs: null, viewers: new Set(), displayName: name || id.slice(0, 8) });
    return screenChannels.get(id)!;
}

function cleanupIfEmpty(id: string): void {
    const sc = screenChannels.get(id);
    if (sc && !sc.senderWs && sc.viewers.size === 0) {
        screenChannels.delete(id);
        log.info('Screen channel evicted', { channelId: id.slice(0, 8) });
    }
}

export class ScreenModule extends ServerModule {
    get name(): string { return 'Screen'; }

    register(ctx: ServerContext): void {
        // Use process.cwd() (always = server/ directory) so the path is correct
        // in BOTH ts-node dev mode AND compiled mode.
        // __dirname-relative ../../.. resolves to dist/ in compiled mode, not server/.
        const htmlPath = path.join(process.cwd(), 'public', 'screen.html');

        // GET /screen-view — serve browser viewer page
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/screen-view', (_req: IncomingMessage, res: ServerResponse) => {
            fs.readFile(htmlPath, (err, data) => {
                if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('screen.html not found'); return; }
                res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                res.end(data);
            });
        }));

        // WS /screen — phone sender
        ctx.addWsRoute(new WsRouteDefinition('/screen', (ws: WebSocket, req: IncomingMessage) => {
            const p    = new URL(req.url ?? '/', 'http://localhost').searchParams;
            const id   = p.get('id');
            const name = p.get('name') ?? '';
            if (!id) { ws.close(1008, 'Missing id'); return; }
            const sc = getOrCreate(id, name);
            if (sc.senderWs?.readyState === WebSocket.OPEN) {
                log.warn('Screen sender replaced', { channelId: id.slice(0, 8) });
                sc.senderWs.close(1000, 'Replaced');
            }
            sc.senderWs = ws;
            log.info('Screen sender connected', { channelId: id.slice(0, 8), name });
            ws.on('message', (data: Buffer, isBinary: boolean) => {
                if (!isBinary) return;
                for (const v of sc.viewers) {
                    if (v.readyState === WebSocket.OPEN) { try { v.send(data, { binary: true }); } catch { /* ignore */ } }
                    else sc.viewers.delete(v);
                }
            });
            ws.on('close', () => {
                if (sc.senderWs === ws) sc.senderWs = null;
                log.info('Screen sender disconnected', { channelId: id.slice(0, 8) });
                const msg = JSON.stringify({ type: 'senderDisconnected' });
                for (const v of sc.viewers) { try { v.send(msg); } catch { /* ignore */ } }
                cleanupIfEmpty(id);
            });
            ws.on('error', (e: Error) => log.error('Screen sender error', { channelId: id.slice(0, 8), error: e.message }));
        }));

        // WS /screen-watch — browser viewer
        ctx.addWsRoute(new WsRouteDefinition('/screen-watch', (ws: WebSocket, req: IncomingMessage) => {
            const id = new URL(req.url ?? '/', 'http://localhost').searchParams.get('id');
            if (!id) { ws.close(1008, 'Missing id'); return; }
            const sc = getOrCreate(id);
            sc.viewers.add(ws);
            log.info('Screen viewer connected', { channelId: id.slice(0, 8), viewers: sc.viewers.size });
            try { ws.send(JSON.stringify({ type: 'senderStatus', connected: sc.senderWs?.readyState === WebSocket.OPEN })); } catch { /* ignore */ }
            ws.on('close', () => {
                sc.viewers.delete(ws);
                log.info('Screen viewer disconnected', { channelId: id.slice(0, 8), viewers: sc.viewers.size });
                cleanupIfEmpty(id);
            });
            ws.on('error', (e: Error) => { log.error('Screen viewer error', { error: e.message }); sc.viewers.delete(ws); });
        }));

        ctx.addHealthCheck(new HealthCheck('screen-channels', async () => ({
            status: 'ok' as const,
            activeScreens: screenChannels.size,
            totalViewers: [...screenChannels.values()].reduce((s, c) => s + c.viewers.size, 0),
        })));

        log.info('ScreenModule registered', { routes: 'GET /screen-view  WS /screen  WS /screen-watch' });
    }
}

export default ScreenModule;
