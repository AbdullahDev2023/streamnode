/**
 * core/HealthCheck.ts — Value object wrapping one named health-check function.
 */
import type { ServerContext } from './ServerContext';

export interface HealthResult {
    status: 'ok' | 'error';
    [key: string]: unknown;
}

type CheckFn = (ctx: ServerContext) => Promise<HealthResult>;

export class HealthCheck {
    readonly name: string;
    private readonly _check: CheckFn;

    constructor(name: string, checkFn: CheckFn) {
        if (!name || typeof name !== 'string') {
            throw new TypeError('HealthCheck: name must be a non-empty string');
        }
        if (typeof checkFn !== 'function') {
            throw new TypeError(`HealthCheck(${name}): checkFn must be a function`);
        }
        this.name   = name;
        this._check = checkFn;
    }

    async run(ctx: ServerContext): Promise<HealthResult> {
        try {
            const result = await this._check(ctx);
            return { ...result, status: result.status ?? 'ok' } as HealthResult;
        } catch (err: unknown) {
            return { status: 'error', reason: (err as Error).message };
        }
    }
}
