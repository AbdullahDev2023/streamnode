/**
 * routes/http.ts — All HTTP route handlers.
 */
import fs      from 'fs';
import path    from 'path';
import crypto  from 'crypto';
import WebSocket from 'ws';
import type { IncomingMessage, ServerResponse } from 'http';

import { forModule } from '../logger';
import * as channels from '../channels';
import * as firebase from '../firebase';
import { applyCors, handlePreflight } from '../middleware/cors';
import { attachRequestId } from '../middleware/requestId';
import * as metrics from '../services/metrics';

const logger = forModule('HTTP');
// Use process.cwd() (always = server/ directory) so the path is correct in
// BOTH compiled mode (dist/src/routes) AND ts-node dev mode (src/routes).
// __dirname-relative paths with ../../.. would land at project root in ts-node.
const PUBLIC_DIR      = path.join(process.cwd(), 'public');
const globalStartTime = Date.now();

// ── Static file helper ────────────────────────────────────────────────────────

function serveFile(res: ServerResponse, filename: string, contentType: string): void {
    const filepath = path.join(PUBLIC_DIR, filename);
    if (!fs.existsSync(filepath)) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end(`Not found: ${filename}`);
        return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filepath)
        .on('error', (err) => {
            logger.error('Static file read error', { filepath, error: err.message });
            try { if (!res.writableEnded) res.end(); } catch { /* ignore */ }
        })
        .pipe(res);
}

// ── Main handler ──────────────────────────────────────────────────────────────

export async function handler(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const u = new URL(req.url ?? '/', 'http://localhost');
    attachRequestId(req, res);
    applyCors(res);
    metrics.increment('httpRequests');

    if (handlePreflight(req, res)) return;

    const { pathname } = u;

    // ── Static pages ──────────────────────────────────────────────────────────
    if (pathname === '/' || pathname === '/index.html') {
        return serveFile(res, 'index.html', 'text/html; charset=utf-8');
    }
    if (pathname === '/player' || pathname === '/player.html') {
        return serveFile(res, 'player.html', 'text/html; charset=utf-8');
    }
    if (pathname === '/pip' || pathname === '/pip.html') {
        return serveFile(res, 'pip.html', 'text/html; charset=utf-8');
    }
    if (pathname === '/screen-view' || pathname === '/screen.html') {
        return serveFile(res, 'screen.html', 'text/html; charset=utf-8');
    }
    if (pathname === '/camera' || pathname === '/camera.html') {
        return serveFile(res, 'camera.html', 'text/html; charset=utf-8');
    }

    // ── GET /recordings/:filename — serve recorded audio files ───────────────
    if (pathname.startsWith('/recordings/')) {
        const filename = path.basename(pathname);            // strip any path traversal
        const filepath = path.join(process.cwd(), 'recordings', filename);
        if (!fs.existsSync(filepath)) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            return void res.end('Recording not found');
        }
        res.writeHead(200, {
            'Content-Type':        'audio/ogg',
            'Content-Disposition': `attachment; filename="${filename}"`,
        });
        fs.createReadStream(filepath).pipe(res);
        return;
    }

    // ── GET /streams — active channel list (includes telemetry + location) ────
    if (pathname === '/streams') {
        const list = [...channels.streams.values()].map(ch => ({
            id:              ch.id,
            displayName:     ch.displayName,
            phoneConnected:  channels.isPhoneConnected(ch),
            streamingActive: ch.streamingActive,
            listeners:       ch.browsers.size,
            framesRelayed:   ch.stats.framesRelayed,
            kbRelayed:       (ch.stats.bytesRelayed / 1024).toFixed(1),
            connectedAt:     ch.stats.connectedAt,
            disconnectedAt:  ch.stats.disconnectedAt,
            // FIX: include telemetry & location so dashboard map/strip seeds on first load
            telemetry:       ch.telemetry,
            location:        ch.location,
            // Phase 4: transport visibility — seeded on page load before WS push arrives
            transportMode:   ch.transportMode,
            transportRttMs:  ch.transportRttMs,
            iceState:        ch.iceState,
            // BUG FIX 7 — include silenceActive so the dashboard shows the correct
            // silence badge on initial page load before the first statsInterval push.
            silenceActive:   ch.silenceActive,
        }));
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return void res.end(JSON.stringify(list));
    }

    // ── GET /health ───────────────────────────────────────────────────────────
    if (pathname === '/health') {
        let livePhones = 0, streaming = 0, totalFrames = 0, totalBytes = 0;
        for (const ch of channels.streams.values()) {
            if (channels.isPhoneConnected(ch)) livePhones++;
            if (ch.streamingActive) streaming++;
            totalFrames += ch.stats.framesRelayed;
            totalBytes  += ch.stats.bytesRelayed;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return void res.end(JSON.stringify({
            status:        'ok',
            version:       '5.2',
            uptimeSeconds: Math.floor((Date.now() - globalStartTime) / 1000),
            totalChannels: channels.streams.size,
            livePhones,
            streaming,
            totalBrowsers: [...channels.streams.values()].reduce((s, c) => s + c.browsers.size, 0),
            totalFrames,
            kbRelayed:     (totalBytes / 1024).toFixed(1),
        }));
    }

    // ── GET /metrics — JSON snapshot (FIX: was implemented but never wired) ──
    if (pathname === '/metrics' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return void res.end(JSON.stringify(metrics.snapshot()));
    }

    // ── GET /metrics/prometheus — Prometheus text exposition format ───────────
    if (pathname === '/metrics/prometheus' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
        return void res.end(metrics.toPrometheus());
    }

    // ── POST /stream-control ──────────────────────────────────────────────────
    if (pathname === '/stream-control' && req.method === 'POST') {
        const id         = u.searchParams.get('id');
        const action     = u.searchParams.get('action');
        const payloadUrl = u.searchParams.get('url') || '';
        const ch         = id ? channels.streams.get(id) : null;
        const commandId  = crypto.randomUUID();

        if (!ch) {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({ error: 'channel not found' }));
        }

        // Bug #3 fix: reject requests where action is missing or empty so we never
        // queue a blank-action Firebase command that the Android client will ignore.
        if (!action) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({ error: 'missing ?action= parameter' }));
        }

        if (ch.controlWs?.readyState === WebSocket.OPEN) {
            try {
                ch.controlWs.send(JSON.stringify({
                    type: 'cmd', commandId, action, url: payloadUrl, source: 'websocket',
                }));
                res.writeHead(200, { 'Content-Type': 'application/json' });
                return void res.end(JSON.stringify({ ok: true, commandId, channel: 'websocket', action }));
            } catch (e: unknown) {
                logger.error(`controlWs send failed, falling back to Firebase: ${(e as Error).message}`);
            }
        }

        const wrote = await firebase.writeFirebaseCommand(id!, commandId, action, payloadUrl);
        if (wrote) {
            res.writeHead(202, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({
                ok: true, commandId, channel: 'firebase', action,
                note: 'phone offline — command queued in Firebase RTDB',
            }));
        }

        res.writeHead(409, { 'Content-Type': 'application/json' });
        return void res.end(JSON.stringify({
            error: 'phone offline',
            hint:  'set FIREBASE_DB_SECRET in server/.env to enable queued commands',
        }));
    }

    // ── POST /wake ────────────────────────────────────────────────────────────
    if (pathname === '/wake' && req.method === 'POST') {
        const { adminDb } = firebase;
        if (!adminDb) {
            res.writeHead(503, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({
                error: 'Firebase Admin not configured',
                hint:  'place serviceAccount.json in server/config/ and restart',
            }));
        }

        const id = u.searchParams.get('id');
        if (!id) {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({ error: 'missing ?id=' }));
        }

        const commandId = crypto.randomUUID();
        try {
            await adminDb.ref(`/users/${id}/control`).set({
                commandId, command: 'reconnect', url: '', ts: Date.now(),
                source: 'wake', processed: false, processedAt: null,
            });
            logger.info(`/wake sent to ${id.slice(0, 8)}`);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({
                ok: true, commandId, action: 'reconnect', channel: 'firebase-admin',
            }));
        } catch (e: unknown) {
            logger.error(`/wake Firebase write failed: ${(e as Error).message}`);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            return void res.end(JSON.stringify({ error: (e as Error).message }));
        }
    }

    res.writeHead(404);
    res.end('Not found');
}
