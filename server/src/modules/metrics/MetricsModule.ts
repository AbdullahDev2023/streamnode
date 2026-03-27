/**
 * modules/metrics/MetricsModule.ts — Feature 9 Metrics History (v6)
 *
 * Registers an in-memory ring-buffer per device that stores the last N telemetry
 * snapshots. Exposes three endpoints:
 *
 *   GET /metrics/:id/history   — last N snapshots for one device (JSON array)
 *   GET /metrics/summary       — aggregate stats across all live devices
 *   GET /metrics/:id/stream    — Server-Sent Events live telemetry stream
 */
import type { IncomingMessage, ServerResponse } from 'http';
import { ServerModule }        from '../../core/ServerModule';
import { HttpRouteDefinition } from '../../core/HttpRouteDefinition';
import type { ServerContext }   from '../../core/ServerContext';
import type { Telemetry }       from '../../channels';
import * as channels            from '../../channels';
import { applyCors, handlePreflight } from '../../middleware/cors';
import { attachRequestId }      from '../../middleware/requestId';
import { forModule }            from '../../logger';
import config                   from '../../config';

const logger = forModule('Metrics');

interface TimestampedTelemetry extends Telemetry {
    deviceId:   string;
    recordedAt: number;
}

const MAX_HISTORY = config.METRICS_HISTORY_SIZE; // configurable via METRICS_HISTORY_SIZE env var (default 60 = 1h at 60s interval; halved from 120 to cut per-device RAM)

export class MetricsModule extends ServerModule {
    get name(): string { return 'Metrics'; }

    /** deviceId → ring-buffer of telemetry snapshots */
    private readonly history = new Map<string, TimestampedTelemetry[]>();

    /** deviceId → Set of SSE response objects for live streaming */
    private readonly sseClients = new Map<string, Set<ServerResponse>>();

    constructor() {
        super();
        _instance = this; // expose singleton for websocket.ts
    }

    // ── Hook called by websocket.ts after every telemetry update ─────────────

    recordTelemetry(deviceId: string, t: Telemetry): void {
        const buf = this.history.get(deviceId) ?? [];
        buf.push({ ...t, deviceId, recordedAt: Date.now() });
        if (buf.length > MAX_HISTORY) buf.shift();
        this.history.set(deviceId, buf);

        // Push to any SSE listeners for this device
        const clients = this.sseClients.get(deviceId);
        if (clients && clients.size > 0) {
            const data = `data: ${JSON.stringify({ ...t, deviceId })}\n\n`;
            for (const res of clients) {
                try { res.write(data); } catch { clients.delete(res); }
            }
        }
    }

    // ── Module registration ───────────────────────────────────────────────────

    register(ctx: ServerContext): void {
        ctx.addHttpRoute(new HttpRouteDefinition('GET', /^\/metrics\/[^/]+\/history$/, this.handleHistory.bind(this)));
        ctx.addHttpRoute(new HttpRouteDefinition('GET', /^\/metrics\/[^/]+\/stream$/,  this.handleStream.bind(this)));
        ctx.addHttpRoute(new HttpRouteDefinition('GET', '/metrics/summary',             this.handleSummary.bind(this)));
        logger.info('MetricsModule registered', {
            routes: 'GET /metrics/:id/history  GET /metrics/:id/stream  GET /metrics/summary',
        });
    }

    // ── GET /metrics/:id/history ─────────────────────────────────────────────

    private handleHistory(req: IncomingMessage, res: ServerResponse): void {
        attachRequestId(req, res);
        applyCors(res);
        if (handlePreflight(req, res)) return;

        const parts    = new URL(req.url ?? '/', 'http://localhost').pathname.split('/').filter(Boolean);
        const deviceId = parts[1] ?? '';
        const limit    = parseInt(new URL(req.url ?? '/', 'http://localhost').searchParams.get('limit') ?? '30', 10);

        const buf = this.history.get(deviceId) ?? [];
        const slice = buf.slice(-Math.min(limit, MAX_HISTORY));

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ deviceId, count: slice.length, history: slice }));
    }

    // ── GET /metrics/summary ─────────────────────────────────────────────────

    private handleSummary(_req: IncomingMessage, res: ServerResponse): void {
        applyCors(res);
        const devices = [...channels.streams.values()].map(ch => {
            const buf = this.history.get(ch.id) ?? [];
            const latest = buf[buf.length - 1] ?? null;
            return {
                id:              ch.id,
                displayName:     ch.displayName,
                phoneConnected:  channels.isPhoneConnected(ch),
                streamingActive: ch.streamingActive,
                historyCount:    buf.length,
                latestTelemetry: latest,
            };
        });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ deviceCount: devices.length, devices }));
    }

    // ── GET /metrics/:id/stream (SSE) ────────────────────────────────────────

    private handleStream(req: IncomingMessage, res: ServerResponse): void {
        applyCors(res);
        const parts    = new URL(req.url ?? '/', 'http://localhost').pathname.split('/').filter(Boolean);
        const deviceId = parts[1] ?? '';

        res.writeHead(200, {
            'Content-Type':  'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection':    'keep-alive',
            'X-Accel-Buffering': 'no',
        });
        res.write(': connected\n\n');

        if (!this.sseClients.has(deviceId)) this.sseClients.set(deviceId, new Set());
        this.sseClients.get(deviceId)!.add(res);
        logger.info('SSE client connected', { deviceId: deviceId.slice(0, 8) });

        req.on('close', () => {
            this.sseClients.get(deviceId)?.delete(res);
            logger.info('SSE client disconnected', { deviceId: deviceId.slice(0, 8) });
        });
    }
}

export default MetricsModule;

// ── Singleton accessor ────────────────────────────────────────────────────────
// websocket.ts imports this to record telemetry into the ring buffer without
// needing access to the ServerContext module registry.
let _instance: MetricsModule | null = null;

export function getMetricsModule(): MetricsModule | null { return _instance; }
