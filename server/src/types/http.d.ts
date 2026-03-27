/**
 * src/types/http.d.ts — Augments Node's IncomingMessage with AuraCast fields.
 */
import 'http';

declare module 'http' {
    interface IncomingMessage {
        /** Attached by middleware/requestId.ts — short UUID for log correlation. */
        requestId?: string;
    }
}
