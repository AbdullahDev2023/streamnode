/**
 * middleware/requestId.ts — Per-request unique ID injection.
 */
import { randomUUID } from 'crypto';
import type { IncomingMessage, ServerResponse } from 'http';

export function attachRequestId(req: IncomingMessage, res: ServerResponse): string {
    const id = randomUUID().slice(0, 8);
    (req as IncomingMessage & { requestId: string }).requestId = id;
    res.setHeader('X-Request-Id', id);
    return id;
}
