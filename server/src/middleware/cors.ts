/**
 * middleware/cors.ts — Centralised CORS header injection.
 */
import type { IncomingMessage, ServerResponse } from 'http';

const HEADERS: Record<string, string> = {
    'Access-Control-Allow-Origin':  '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Request-Id, Authorization',
    'X-Content-Type-Options':       'nosniff',
    'X-Frame-Options':              'DENY',
};

export function applyCors(res: ServerResponse): void {
    for (const [k, v] of Object.entries(HEADERS)) res.setHeader(k, v);
}

export function handlePreflight(req: IncomingMessage, res: ServerResponse): boolean {
    if (req.method !== 'OPTIONS') return false;
    // Write CORS headers so the browser accepts the preflight.
    applyCors(res);
    res.writeHead(204);
    res.end();
    return true;
}
