/**
 * src/firebase.ts — Firebase Admin SDK initialisation & RTDB helpers.
 */
import path   from 'path';
import config from './config';
import { forModule } from './logger';
import type { database } from 'firebase-admin';

const logger = forModule('Firebase');

// ── Admin SDK init (optional) ─────────────────────────────────────────────────

export let adminDb: database.Database | null = null;

try {
    // BUG FIX: Use process.cwd() (always = server/ directory in both ts-node dev
    // mode and compiled node dist/server.js mode) instead of a __dirname-relative
    // path.  In compiled mode __dirname = server/dist/src/, so require('../config/')
    // resolves to server/dist/config/ which doesn't exist — the serviceAccount.json
    // is at server/config/serviceAccount.json.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const admin      = require('firebase-admin') as typeof import('firebase-admin');
    const svcPath    = path.resolve(process.cwd(), 'config', 'serviceAccount.json');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const svcAccount = require(svcPath) as object;
    admin.initializeApp({
        credential:  admin.credential.cert(svcAccount),
        databaseURL: config.FIREBASE_DB_URL,
    });
    adminDb = admin.database() as database.Database;
    logger.info('Firebase Admin initialized', { dbUrl: config.FIREBASE_DB_URL });
} catch (e: unknown) {
    logger.warn('Firebase Admin not available — /wake and admin Firebase fallback disabled', { error: (e as Error).message });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

export async function writeFirebaseCommand(
    streamId:  string,
    commandId: string,
    action:    string,
    url        = '',
): Promise<boolean> {
    if (!config.FIREBASE_SECRET) {
        logger.warn('writeFirebaseCommand: FIREBASE_DB_SECRET is not set — cannot queue offline command', { action, streamId: streamId.slice(0, 8) });
        return false;
    }
    try {
        const endpoint = `${config.FIREBASE_DB_URL}/users/${streamId}/control.json?auth=${config.FIREBASE_SECRET}`;
        const body = JSON.stringify({
            commandId, command: action, url, ts: Date.now(),
            source: 'firebase', processed: false, processedAt: null,
        });
        const res = await fetch(endpoint, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body,
        });
        if (!res.ok) {
            logger.error('Firebase REST write failed', { status: res.status, action });
            return false;
        }
        logger.info('Firebase fallback command queued', {
            action, commandId: commandId.slice(0, 8), streamId: streamId.slice(0, 8),
        });
        return true;
    } catch (e: unknown) {
        logger.error('Firebase REST write error', { error: (e as Error).message });
        return false;
    }
}

// ── Gap 2 / Gap 6: server-online flag & startup timestamp ─────────────────────

/**
 * Writes /auracast_config/serverOnline and /auracast_config/serverStartedAt to
 * Firebase RTDB via the Admin SDK (no REST secret needed).
 *
 * Called by ReconnectModule.onStart() (online=true, ts=Date.now()) and by the
 * SIGINT handler in server.ts (online=false) so Android clients know when the
 * server has restarted without polling.
 *
 * Returns true on success, false if Firebase Admin is unavailable.
 */
export async function writeServerOnline(online: boolean, startedAt?: number): Promise<boolean> {
    if (!adminDb) {
        logger.warn('writeServerOnline: Firebase Admin not available — skipping');
        return false;
    }
    try {
        const configRef = adminDb.ref('auracast_config');
        const updates: Record<string, unknown> = { serverOnline: online };
        if (online && startedAt != null) {
            updates['serverStartedAt'] = startedAt;
        }
        await configRef.update(updates);
        logger.info('writeServerOnline', { online, startedAt });
        return true;
    } catch (e: unknown) {
        logger.error('writeServerOnline failed', { error: (e as Error).message });
        return false;
    }
}
