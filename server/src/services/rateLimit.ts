/**
 * services/rateLimit.ts — Sliding-window per-IP rate limiter.
 */
import type { IncomingMessage, ServerResponse } from 'http';
import config from '../config';
import { forModule } from '../logger';

const logger = forModule('RateLimit');

const ipWindows = new Map<string, number[]>();

export function check(req: IncomingMessage, res: ServerResponse): boolean {
    if (!config.RATE_LIMIT_ENABLED) return false;

    const ip          = req.socket?.remoteAddress || 'unknown';
    const now         = Date.now();
    const windowStart = now - config.RATE_WINDOW_MS;

    let timestamps = ipWindows.get(ip) || [];
    timestamps = timestamps.filter(t => t > windowStart);

    if (timestamps.length >= config.MAX_REQUESTS_PER_WINDOW) {
        logger.warn(`Rate limit hit: ip=${ip} count=${timestamps.length}`);
        res.writeHead(429, {
            'Content-Type': 'application/json',
            'Retry-After':  String(Math.ceil(config.RATE_WINDOW_MS / 1000)),
        });
        res.end(JSON.stringify({
            error:      'Too many requests',
            retryAfter: Math.ceil(config.RATE_WINDOW_MS / 1000),
        }));
        return true;
    }

    timestamps.push(now);
    ipWindows.set(ip, timestamps);
    return false;
}

export function purgeStale(): void {
    const cutoff = Date.now() - config.RATE_WINDOW_MS;
    for (const [ip, ts] of ipWindows) {
        const fresh = ts.filter(t => t > cutoff);
        if (fresh.length === 0) ipWindows.delete(ip);
        else ipWindows.set(ip, fresh);
    }
}
