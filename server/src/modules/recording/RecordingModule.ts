/**
 * modules/recording/RecordingModule.ts — Server-Side Recording (Feature 3, v5.2).
 * Bug fix: -f opus (raw Opus bitstream) instead of the old -f ogg (OGG container).
 */
import fs   from 'fs';
import path from 'path';
import { spawn, ChildProcess } from 'child_process';
import { PassThrough } from 'stream';
import type { IncomingMessage, ServerResponse } from 'http';

import { ServerModule }        from '../../core/ServerModule';
import { HttpRouteDefinition } from '../../core/HttpRouteDefinition';
import type { ServerContext }   from '../../core/ServerContext';
import * as channels from '../../channels';
import type { CodecConfig } from '../../channels';
import { adminAuth }   from '../../middleware/adminAuth';
import { applyCors, handlePreflight } from '../../middleware/cors';
import { attachRequestId } from '../../middleware/requestId';
import { forModule } from '../../logger';

const logger = forModule('Recording');

// Use process.cwd() (= server/ dir) so the path is correct in both compiled
// (dist/src/modules/recording → ../../../ = server/dist, wrong) and ts-node mode.
const RECORDINGS_DIR = process.env.RECORDINGS_DIR
    ? path.resolve(process.env.RECORDINGS_DIR)
    : path.resolve(process.cwd(), 'recordings');

fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
logger.info('Recordings directory ready', { path: RECORDINGS_DIR });

function json(res: ServerResponse, status: number, body: object): void {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
}
function extractId(pathname: string): string | null {
    const parts = pathname.split('/').filter(Boolean);
    return parts.length >= 2 ? parts[1] : null;
}

async function startRecording(deviceId: string): Promise<string> {
    const ch = channels.streams.get(deviceId);
    if (!ch) throw new Error('no active channel for this device');
    if (ch.recording.active) throw new Error('already recording');

    const sr       = (ch.codecConfig as CodecConfig | null)?.sampleRate ?? 48000;
    const ts       = new Date().toISOString().replace(/[:.]/g, '-');
    const filePath = path.join(RECORDINGS_DIR, `${deviceId.slice(0, 8)}_${ts}.ogg`);

    const ffmpegStderr = new PassThrough();
    ffmpegStderr.on('data', (chunk: Buffer) => {
        const line = chunk.toString().trim();
        if (line) logger.debug('ffmpeg', { deviceId: deviceId.slice(0, 8), output: line });
    });

    const proc: ChildProcess = spawn('ffmpeg', [
        '-y',
        // Bug #6 fix: -ar MUST precede -f/-i to be treated as an input option.
        // Placing it between -f opus and -i pipe:0 causes some ffmpeg builds to
        // interpret it as an output option (where it is silently ignored for the
        // input demuxer), producing a mis-timed or corrupt recording.
        '-ar', String(sr),
        '-f',  'opus',
        '-i',  'pipe:0',
        '-c:a', 'libopus',
        '-f',  'ogg',
        filePath,
    ], { stdio: ['pipe', 'ignore', 'pipe'] });

    (proc.stderr as NodeJS.ReadableStream).pipe(ffmpegStderr);

    proc.on('error', (err: Error) => {
        logger.error('ffmpeg spawn error', { deviceId: deviceId.slice(0, 8), error: err.message });
        ch.recording.active = false; ch.recording.frameHook = null;
    });
    proc.on('close', (code: number | null) => {
        logger.info('ffmpeg closed', { deviceId: deviceId.slice(0, 8), exitCode: code, filePath });
        ch.recording.active = false; ch.recording.ffmpegProc = null; ch.recording.frameHook = null;
    });

    ch.recording.active = true; ch.recording.ffmpegProc = proc;
    ch.recording.filePath = filePath; ch.recording.startedAt = Date.now();

    // OPT: track dropped frames so slow-disk situations don't grow Node.js heap unboundedly.
    let droppedFrames = 0;

    ch.recording.frameHook = (frame: Buffer) => {
        const stdin = proc.stdin as NodeJS.WritableStream;
        if (!stdin.writable) return;
        // Backpressure guard: if the write buffer is full, drop the frame rather than
        // queuing it in memory. A warning is logged once every 100 dropped frames.
        if ((proc.stdin as import('stream').Writable).writableNeedDrain) {
            droppedFrames++;
            if (droppedFrames % 100 === 1) {
                logger.warn('ffmpeg stdin full — dropping frames (disk too slow?)', {
                    deviceId: deviceId.slice(0, 8), droppedFrames,
                });
            }
            return;
        }
        stdin.write(frame);
    };

    logger.info('Recording started', { deviceId: deviceId.slice(0, 8), filePath });
    return filePath;
}

function stopRecording(deviceId: string): { filePath: string; durationSec: number } {
    const ch = channels.streams.get(deviceId);
    if (!ch) throw new Error('no active channel for this device');
    if (!ch.recording.active) throw new Error('not currently recording');

    const { filePath, startedAt, ffmpegProc } = ch.recording;
    try { (ffmpegProc!.stdin as NodeJS.WritableStream).end(); } catch { /* ignore */ }

    ch.recording.active = false; ch.recording.frameHook = null; ch.recording.ffmpegProc = null;
    const durationSec = startedAt ? Math.floor((Date.now() - startedAt) / 1000) : 0;
    logger.info('Recording stopped', { deviceId: deviceId.slice(0, 8), durationSec, filePath });
    return { filePath: filePath!, durationSec };
}

async function handleRecordingRequest(req: IncomingMessage, res: ServerResponse): Promise<void> {
    attachRequestId(req, res);
    applyCors(res);
    if (handlePreflight(req, res)) return;
    if (!adminAuth(req, res)) return;

    const u        = new URL(req.url ?? '/', 'http://localhost');
    const deviceId = extractId(u.pathname);
    if (!deviceId) return json(res, 400, { error: 'missing device id in path' });

    const parts     = u.pathname.split('/').filter(Boolean);
    const subAction = parts[parts.length - 1];
    const method    = req.method?.toUpperCase();

    try {
        if (subAction === 'start' && method === 'POST') {
            const filePath = await startRecording(deviceId);
            return json(res, 200, { ok: true, filePath, startedAt: channels.streams.get(deviceId)?.recording.startedAt });
        }
        if (subAction === 'stop' && method === 'POST') {
            return json(res, 200, { ok: true, ...stopRecording(deviceId) });
        }
        if (subAction === 'status' && method === 'GET') {
            const ch = channels.streams.get(deviceId);
            if (!ch) return json(res, 404, { error: 'device not found' });
            return json(res, 200, {
                active: ch.recording.active, filePath: ch.recording.filePath,
                startedAt: ch.recording.startedAt,
                durationSec: ch.recording.startedAt ? Math.floor((Date.now() - ch.recording.startedAt) / 1000) : null,
            });
        }
        return json(res, 404, { error: `unknown recording action: "${subAction}"` });
    } catch (e: unknown) {
        logger.error('Recording request error', { deviceId: deviceId.slice(0, 8), subAction, error: (e as Error).message });
        return json(res, 409, { error: (e as Error).message });
    }
}

export class RecordingModule extends ServerModule {
    get name() { return 'Recording'; }
    register(ctx: ServerContext): void {
        ctx.addHttpRoute(new HttpRouteDefinition('*', /^\/admin\/[^/]+\/record\/(start|stop|status)$/, handleRecordingRequest));
        logger.info('RecordingModule registered', { routes: 'POST /admin/:id/record/{start,stop}  GET /admin/:id/record/status' });
    }
}

export default RecordingModule;
