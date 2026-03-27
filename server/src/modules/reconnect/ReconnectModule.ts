/**
 * modules/reconnect/ReconnectModule.ts
 *
 * Gap 2 / Gap 6 fix — server-restart wake blast.
 *
 * On server startup (onStart):
 *   1. Writes /auracast_config/serverStartedAt = Date.now() to Firebase RTDB.
 *      Every connected Android device listens to this path and calls
 *      reconnectNow() immediately (< 3 s via Firebase push), bypassing the
 *      up-to-5-minute Phase-2 backoff delay.
 *   2. Writes /auracast_config/serverOnline = true.
 *   3. Reads /users/ and sends a Firebase "reconnect" command to each known
 *      device (wake blast), so devices with no active connection also recover.
 *
 * On graceful shutdown (onStop):
 *   Writes /auracast_config/serverOnline = false so dashboards can show that
 *   the server went offline intentionally.
 *
 * Module name "reconnect" sorts alphabetically before "screen" and "streaming",
 * so load order is always correct (streaming catch-all stays last).
 */
import crypto from 'crypto';

import { ServerModule }       from '../../core/ServerModule';
import type { ServerContext } from '../../core/ServerContext';
import { writeServerOnline, writeFirebaseCommand, adminDb } from '../../firebase';
import { forModule } from '../../logger';

const log = forModule('Reconnect');

export class ReconnectModule extends ServerModule {
    get name() { return 'Reconnect'; }

    /** No HTTP/WS routes — this module only uses lifecycle hooks. */
    register(_ctx: ServerContext): void {
        log.info('ReconnectModule registered (startup wake-blast active)');
    }

    /**
     * onStart — fires after server.listen() completes.
     *
     * 1. Write serverStartedAt + serverOnline=true to Firebase.
     * 2. Read /users/ and send a "reconnect" command to every known device.
     *    Devices with an active WS connection ignore the Firebase command
     *    (they already reconnected via the startedAt watcher); devices that
     *    are truly offline and missed the watcher event get the command queued
     *    and delivered when they next come online.
     */
    async onStart(ctx: ServerContext): Promise<void> {
        if (!ctx.config.RECONNECT_ON_STARTUP) {
            log.info('RECONNECT_ON_STARTUP=false — wake blast skipped');
            return;
        }
        if (!adminDb) {
            log.warn('Firebase Admin not available — wake blast skipped (no serviceAccount.json?)');
            return;
        }

        const startedAt = Date.now();

        // Step 1: stamp serverStartedAt + serverOnline=true
        const stamped = await writeServerOnline(true, startedAt);
        if (stamped) {
            log.info('Wrote serverStartedAt + serverOnline=true to Firebase', { startedAt });
        }

        // Step 2: enumerate /users/ and send a reconnect command to each device
        try {
            const snapshot = await adminDb.ref('users').once('value');
            const users    = snapshot.val() as Record<string, unknown> | null;
            if (!users) {
                log.info('No known devices in /users/ — wake blast not needed');
                return;
            }

            const deviceIds = Object.keys(users).slice(0, ctx.config.MAX_WAKE_DEVICES);
            log.info(`Wake blast: sending reconnect to ${deviceIds.length} device(s)`, {
                total: Object.keys(users).length,
                capped: ctx.config.MAX_WAKE_DEVICES,
            });

            let succeeded = 0;
            let failed    = 0;
            await Promise.all(deviceIds.map(async (id) => {
                const commandId = crypto.randomUUID();
                const ok = await writeFirebaseCommand(id, commandId, 'reconnect', '');
                if (ok) succeeded++; else failed++;
            }));

            log.info('Wake blast complete', { succeeded, failed });
        } catch (e: unknown) {
            log.error('Wake blast failed — could not read /users/', {
                error: (e as Error).message,
            });
        }
    }

    /**
     * onStop — fires on SIGINT before the HTTP server closes.
     * Marks the server as offline in Firebase so dashboards update immediately.
     */
    async onStop(_ctx: ServerContext): Promise<void> {
        log.info('Server shutting down — writing serverOnline=false to Firebase');
        await writeServerOnline(false);
    }
}

export default ReconnectModule;
