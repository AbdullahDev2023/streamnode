/**
 * middleware/validate.ts — Query-parameter validation helpers.
 */
import type { ServerResponse } from 'http';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface ValidationResult {
    ok:     boolean;
    error?: string;
}

export function requireId(id: string | null): ValidationResult {
    if (!id)              return { ok: false, error: 'Missing required query param: id' };
    if (!UUID_RE.test(id)) return { ok: false, error: 'Invalid id format — expected UUID' };
    return { ok: true };
}

export function requireAction(action: string | null): ValidationResult {
    const ALLOWED = new Set(['start', 'stop', 'reconnect', 'change_url']);
    if (!action)               return { ok: false, error: 'Missing required query param: action' };
    if (!ALLOWED.has(action))  return { ok: false, error: `Unknown action '${action}'. Allowed: ${[...ALLOWED].join(', ')}` };
    return { ok: true };
}

export function badRequest(res: ServerResponse, message: string): void {
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: message }));
}
