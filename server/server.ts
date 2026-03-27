/**
 * server.ts — AuraCast Server v5.2 (TypeScript entry point)
 *
 * Run (dev):   npx ts-node --transpile-only server.ts
 * Run (prod):  npm run build && node dist/server.js
 * Watch:       npm run dev:watch
 */
import http      from 'http';
import path      from 'path';
import WebSocket from 'ws';

import config   from './src/config';
import { forModule } from './src/logger';
import * as channels from './src/channels';
import * as rateLimit from './src/services/rateLimit';
import { writeServerOnline } from './src/firebase';

import { ServerContext, ModuleLoader } from './src/core';
import type { ChannelRegistry } from './src/core/ServerContext';

const log = forModule('Server');

// ── Process guards ────────────────────────────────────────────────────────────

process.on('uncaughtException', (err: Error) => {
    log.error('UNCAUGHT EXCEPTION — process will exit', { message: err.message, stack: err.stack });
    process.exit(1);
});

process.on('unhandledRejection', (reason: unknown) => {
    const msg = reason instanceof Error ? reason.stack : String(reason);
    log.error('UNHANDLED REJECTION — process will exit', { detail: msg });
    process.exit(1);
});

// ── HTTP + WebSocket servers ───────────────────────────────────────────────────

const httpServer = http.createServer();
const wss        = new WebSocket.Server({ server: httpServer });

// ── Build ServerContext ───────────────────────────────────────────────────────

const ctx = new ServerContext({
    httpServer,
    wss,
    config,
    logger: { forModule },
    channels: channels as unknown as ChannelRegistry,
});

// ── Load modules ──────────────────────────────────────────────────────────────

const MODULES_DIR = path.join(__dirname, 'src', 'modules');
const loader      = new ModuleLoader(MODULES_DIR);
loader.loadSync(ctx);

// ── Wire dispatchers ──────────────────────────────────────────────────────────

httpServer.on('request', (req, res) => ctx.httpDispatch(req, res));
wss.on('connection',    (ws, req)  => ctx.wsDispatch(ws, req));

// ── Heartbeat — detect dead sockets ──────────────────────────────────────────

const wsHeartbeat = setInterval(() => {
    wss.clients.forEach(ws => {
        const live = ws as WebSocket & { isAlive?: boolean };
        if (live.isAlive === false) { ws.terminate(); return; }
        live.isAlive = false;
        try { ws.ping(); } catch { /* ignore */ }
    });
}, config.WS_PING_INTERVAL_MS);

// ── Stale phone check ─────────────────────────────────────────────────────────

const staleCheck = setInterval(() => {
    for (const [id, ch] of channels.streams) {
        if (!ch.stats.lastActivityMs) continue;
        const idleSec = Math.floor((Date.now() - ch.stats.lastActivityMs) / 1000);
        if ((Date.now() - ch.stats.lastActivityMs) <= config.STALE_THRESHOLD_MS) continue;

        // Terminate stale /stream socket (legacy WS audio path)
        if (ch.phoneWs && ch.phoneWs.readyState === WebSocket.OPEN) {
            log.warn('Stale /stream socket — terminating', { channelId: id.slice(0, 8), idleSec });
            ch.phoneWs.terminate();
        }
        // Terminate stale /control socket (command channel — also primary socket for WebRTC devices)
        if (ch.controlWs && ch.controlWs.readyState === WebSocket.OPEN) {
            log.warn('Stale /control socket — terminating', { channelId: id.slice(0, 8), idleSec });
            ch.controlWs.terminate();
        }
    }
}, config.STALE_CHECK_MS);

// ── Stats broadcast ───────────────────────────────────────────────────────────

const statsInterval = setInterval(() => {
    for (const ch of channels.streams.values()) {
        if (ch.browsers.size === 0) continue;

        // BUG FIX #3 — Phase 6 Step 28 (silence notification protocol):
        // While the phone's VOX gate has soft-suspended AudioRecord (silenceActive=true)
        // send a lightweight silence frame instead of the full ~200-byte stats payload.
        // README spec: "the statsInterval sends a lightweight {type:'silence'} frame to
        // browsers instead of the full stats payload while silenceActive is true".
        // The old code always built the full stats payload, ignoring ch.silenceActive.
        if (ch.silenceActive) {
            const silencePayload = JSON.stringify({ type: 'silence', id: ch.id, active: true });
            if (silencePayload !== ch.lastStatsJson) {
                ch.lastStatsJson = silencePayload;
                channels.broadcast(ch, silencePayload);
            }
            continue;
        }

        const payload = JSON.stringify({
            type:            'stats',
            phoneConnected:  channels.isPhoneConnected(ch),
            streamingActive: ch.streamingActive,
            framesRelayed:   ch.stats.framesRelayed,
            kbRelayed:       (ch.stats.bytesRelayed / 1024).toFixed(1),
            uptimeSec:       ch.stats.connectedAt
                ? Math.floor((Date.now() - ch.stats.connectedAt) / 1000) : 0,
            volumeGain:      config.SERVER_VOLUME_GAIN,
        });
        // OPT: skip broadcast if nothing changed — saves serialisation + WS send per interval
        if (payload === ch.lastStatsJson) continue;
        ch.lastStatsJson = payload;
        channels.broadcast(ch, payload);
    }
}, config.STATS_INTERVAL_MS);

// ── Rate-limit purge ──────────────────────────────────────────────────────────

const rateLimitPurge = setInterval(() => rateLimit.purgeStale(), config.RATE_WINDOW_MS);

// ── Start listening ───────────────────────────────────────────────────────────

httpServer.listen(config.PORT, async () => {
    log.info('');
    log.info('  ╔══════════════════════════════════════╗');
    log.info('  ║   AuraCast Server v5.2 — TypeScript ║');
    log.info('  ╚══════════════════════════════════════╝');
    log.info(`  Dashboard  →  http://localhost:${config.PORT}`);
    log.info(`  Health     →  http://localhost:${config.PORT}/health`);
    log.info('');
    log.info('Server listening', { port: config.PORT, logLevel: config.LOG_LEVEL, pid: process.pid });

    await loader.startAll(ctx);
});

// ── Graceful shutdown ─────────────────────────────────────────────────────────

process.on('SIGINT', async () => {
    log.info('SIGINT — shutting down gracefully', { pid: process.pid });
    clearInterval(wsHeartbeat);
    clearInterval(staleCheck);
    clearInterval(statsInterval);
    clearInterval(rateLimitPurge);
    // Gap 2: mark server offline in Firebase before closing so dashboards update
    // immediately and Android devices stop retrying a dead endpoint.
    await writeServerOnline(false).catch(() => { /* non-fatal */ });
    await loader.stopAll(ctx);
    wss.close();
    httpServer.close(() => { log.info('Server stopped cleanly.'); process.exit(0); });
});
