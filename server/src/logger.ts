/**
 * src/logger.ts — Highest-level structured logger for AuraCast Server v5.2.
 *
 * Features:
 *  • ISO-8601 timestamps with date+time+ms
 *  • PID and process uptime on every line
 *  • Structured key=value fields (object second arg)
 *  • VERBOSE level below DEBUG for frame-level tracing
 *  • JSON_LOGS=true env var → NDJSON output for log aggregators
 *  • child() loggers carry parent fields through cleanly
 *
 * Human-readable format:
 *   [2025-06-15T14:32:01.123Z] [pid=1234] [up=42s] [INFO   ] [Server]  key=val  message
 *
 * NDJSON format (JSON_LOGS=true):
 *   {"ts":"2025-06-15T14:32:01.123Z","pid":1234,"upSec":42,"level":"INFO","module":"Server","key":"val","msg":"..."}
 *
 * Usage:
 *   const log = forModule('WS');
 *   log.info('Connected', { url, attempt: 1 });
 *   log.warn('Timeout', { channelId: id.slice(0,8) });
 *   const reqLog = log.child({ requestId: '3fa8b1c2' });
 *   reqLog.info('Snapshot requested');
 */

const LEVELS: Record<string, number> = { VERBOSE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4 };
const PROC_START = Date.now();
const PID        = process.pid;
const JSON_LOGS  = process.env.JSON_LOGS === 'true';

function minLevel(): number {
    try {
        const cfg = (require('./config').default ?? require('./config')) as { LOG_LEVEL: string };
        return LEVELS[cfg.LOG_LEVEL] ?? LEVELS.DEBUG;
    } catch {
        return LEVELS[process.env.LOG_LEVEL?.toUpperCase() ?? 'DEBUG'] ?? LEVELS.DEBUG;
    }
}

function write(
    level:   string,
    module:  string,
    fields:  Record<string, unknown>,
    msg:     string,
): void {
    if (LEVELS[level] < minLevel()) return;

    const now   = new Date();
    const ts    = now.toISOString();
    const upSec = Math.floor((now.getTime() - PROC_START) / 1000);
    const dest  = level === 'ERROR' ? process.stderr : process.stdout;

    if (JSON_LOGS) {
        dest.write(JSON.stringify({ ts, pid: PID, upSec, level, module, ...fields, msg }) + '\n');
    } else {
        const fieldStr = Object.keys(fields).length
            ? '  ' + Object.entries(fields).map(([k, v]) => `${k}=${v}`).join(' ')
            : '';
        dest.write(`[${ts}] [pid=${PID}] [up=${upSec}s] [${level.padEnd(7)}] [${module}]${fieldStr}  ${msg}\n`);
    }
}

// ── Public interfaces ─────────────────────────────────────────────────────────

export interface ChildLogger {
    verbose: (msg: string, fields?: Record<string, unknown>) => void;
    debug:   (msg: string, fields?: Record<string, unknown>) => void;
    info:    (msg: string, fields?: Record<string, unknown>) => void;
    warn:    (msg: string, fields?: Record<string, unknown>) => void;
    error:   (msg: string, fields?: Record<string, unknown>) => void;
}

export interface Logger extends ChildLogger {
    child: (extra: Record<string, unknown>) => Logger;
}

// ── Factory ───────────────────────────────────────────────────────────────────

function makeLogger(module: string, fixed: Record<string, unknown>): Logger {
    const merge = (f?: Record<string, unknown>) => ({ ...fixed, ...(f ?? {}) });
    return {
        verbose: (msg, f) => write('VERBOSE', module, merge(f), msg),
        debug:   (msg, f) => write('DEBUG',   module, merge(f), msg),
        info:    (msg, f) => write('INFO',    module, merge(f), msg),
        warn:    (msg, f) => write('WARN',    module, merge(f), msg),
        error:   (msg, f) => write('ERROR',   module, merge(f), msg),
        child:   (extra)  => makeLogger(module, { ...fixed, ...extra }),
    };
}

export function forModule(module: string, fixedFields?: Record<string, unknown>): Logger {
    return makeLogger(module, fixedFields ?? {});
}
