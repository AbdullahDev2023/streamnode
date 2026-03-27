/**
 * core/ServerContext.ts — DI container and central dispatcher.
 */
import type { Server as HttpServer, IncomingMessage, ServerResponse } from 'http';
import type { Server as WsServer } from 'ws';
import type WebSocket from 'ws';
import type { AppConfig } from '../config';
import type { Logger } from '../logger';

import { HttpRouteDefinition } from './HttpRouteDefinition';
import { WsRouteDefinition }   from './WsRouteDefinition';
import { HealthCheck }         from './HealthCheck';

export interface ChannelRegistry {
    streams: Map<string, unknown>;
    [key: string]: unknown;
}

export interface LoggerFactory {
    forModule: (name: string) => Logger;
}

export interface ContextDeps {
    httpServer: HttpServer;
    wss:        WsServer;
    config:     AppConfig;
    logger:     LoggerFactory;
    channels:   ChannelRegistry;
}

type HookFn = (ctx: ServerContext) => Promise<void>;

export class ServerContext {
    readonly httpServer: HttpServer;
    readonly wss:        WsServer;
    readonly config:     AppConfig;
    readonly logger:     LoggerFactory;
    readonly channels:   ChannelRegistry;

    private readonly _httpRoutes:   HttpRouteDefinition[];
    private readonly _wsRoutes:     WsRouteDefinition[];
    private readonly _healthChecks: Map<string, HealthCheck>;
    private readonly _startHooks:   HookFn[];
    private readonly _stopHooks:    HookFn[];
    private readonly _log:          Logger;

    constructor({ httpServer, wss, config, logger, channels }: ContextDeps) {
        this.httpServer = httpServer;
        this.wss        = wss;
        this.config     = config;
        this.logger     = logger;
        this.channels   = channels;

        this._httpRoutes   = [];
        this._wsRoutes     = [];
        this._healthChecks = new Map();
        this._startHooks   = [];
        this._stopHooks    = [];
        this._log          = logger.forModule('Context');
    }

    // ── Registration API ──────────────────────────────────────────────────────

    addHttpRoute(def: HttpRouteDefinition): void {
        if (!(def instanceof HttpRouteDefinition)) {
            throw new TypeError('addHttpRoute: argument must be an HttpRouteDefinition');
        }
        this._httpRoutes.push(def);
        this._log.debug('HTTP route registered', { method: def.method, path: String(def.routePath) });
    }

    addWsRoute(def: WsRouteDefinition): void {
        if (!(def instanceof WsRouteDefinition)) {
            throw new TypeError('addWsRoute: argument must be a WsRouteDefinition');
        }
        this._wsRoutes.push(def);
        this._log.debug('WS route registered', { path: String(def.routePath) });
    }

    addHealthCheck(def: HealthCheck): void {
        if (!(def instanceof HealthCheck)) {
            throw new TypeError('addHealthCheck: argument must be a HealthCheck');
        }
        this._healthChecks.set(def.name, def);
        this._log.debug('Health check registered', { name: def.name });
    }

    addStartHook(fn: HookFn): void {
        if (typeof fn !== 'function') throw new TypeError('addStartHook: fn must be a function');
        this._startHooks.push(fn);
    }

    addStopHook(fn: HookFn): void {
        if (typeof fn !== 'function') throw new TypeError('addStopHook: fn must be a function');
        this._stopHooks.push(fn);
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    async httpDispatch(req: IncomingMessage, res: ServerResponse): Promise<void> {
        const pathname = new URL(req.url ?? '/', 'http://localhost').pathname;
        const method   = (req.method ?? 'GET').toUpperCase();

        // Fast-path: answer CORS preflight immediately without hitting every module.
        if (method === 'OPTIONS') {
            res.writeHead(204, {
                'Access-Control-Allow-Origin':  '*',
                'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type,Authorization',
                'Access-Control-Max-Age':        '86400',
            });
            res.end();
            return;
        }

        for (const route of this._httpRoutes) {
            if (route.matches(method, pathname)) {
                try {
                    await route.handler(req, res, this);
                } catch (err: unknown) {
                    this._log.error('Unhandled error in HTTP route', { method, pathname, error: (err as Error).message });
                    if (!res.headersSent) {
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: 'Internal server error' }));
                    }
                }
                return;
            }
        }

        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Not found' }));
    }

    wsDispatch(ws: WebSocket, req: IncomingMessage): void {
        const pathname = new URL(req.url ?? '/', 'http://localhost').pathname;

        for (const route of this._wsRoutes) {
            if (route.matches(pathname)) {
                try {
                    route.handler(ws, req, this);
                } catch (err: unknown) {
                    this._log.error('Unhandled error in WS route', { pathname, error: (err as Error).message });
                    try { ws.close(1011, 'Internal error'); } catch { /* ignore */ }
                }
                return;
            }
        }

        ws.close(1008, 'Unknown path');
    }

    // ── Health ────────────────────────────────────────────────────────────────

    async getHealthStatus(): Promise<{ overall: 'ok' | 'degraded'; checks: Record<string, unknown> }> {
        const entries = await Promise.all(
            [...this._healthChecks.entries()].map(async ([name, hc]) => {
                const result = await hc.run(this);
                return [name, result] as [string, unknown];
            })
        );
        const checks  = Object.fromEntries(entries);
        const overall = entries.every(([, r]) => (r as { status: string }).status === 'ok') ? 'ok' : 'degraded';
        return { overall, checks };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    async runStartHooks(): Promise<void> {
        for (const fn of this._startHooks) await fn(this);
    }

    async runStopHooks(): Promise<void> {
        for (const fn of this._stopHooks) {
            try { await fn(this); } catch (e: unknown) {
                this._log.error('Stop hook error', { error: (e as Error).message });
            }
        }
    }
}
