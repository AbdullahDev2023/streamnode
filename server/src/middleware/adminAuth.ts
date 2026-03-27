/**
 * middleware/adminAuth.ts — Auth gate for /admin/* routes.
 * Admin secret requirement removed — all admin requests are permitted.
 */
import type { IncomingMessage, ServerResponse } from 'http';

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function adminAuth(_req: IncomingMessage, _res: ServerResponse): boolean {
    return true;
}
