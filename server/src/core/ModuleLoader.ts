/**
 * core/ModuleLoader.ts — Auto-discovers and loads ServerModule subclasses.
 *
 * Scans modulesDir recursively for *.ts and *.js files (supporting both
 * ts-node dev mode and compiled production builds).
 */
import fs   from 'fs';
import path from 'path';

import { ServerModule } from './ServerModule';
import { EnvConfig }    from './EnvConfig';
import type { ServerContext } from './ServerContext';

export class ModuleLoader {
    private readonly modulesDir: string;
    private readonly _modules: ServerModule[];

    constructor(modulesDir: string) {
        this.modulesDir = modulesDir;
        this._modules   = [];
    }

    // ── File discovery ────────────────────────────────────────────────────────

    private _collectFiles(dir: string): string[] {
        if (!fs.existsSync(dir)) return [];
        const results: string[] = [];

        const entries = fs.readdirSync(dir, { withFileTypes: true })
            .sort((a, b) => a.name.localeCompare(b.name));

        for (const entry of entries) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                results.push(...this._collectFiles(full));
            } else if (entry.isFile() && (entry.name.endsWith('.js') || entry.name.endsWith('.ts'))) {
                // Skip declaration files — they are not executable modules
                if (entry.name.endsWith('.d.ts')) continue;
                results.push(full);
            }
        }
        return results;
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    loadSync(ctx: ServerContext): void {
        const log   = ctx.logger.forModule('ModuleLoader');
        const files = this._collectFiles(this.modulesDir);

        if (files.length === 0) {
            log.warn('No module files found', { dir: this.modulesDir });
        }

        for (const file of files) {
            let exported: unknown;
            try {
                exported = require(file);
            } catch (err: unknown) {
                throw new Error(`ModuleLoader: failed to require "${file}": ${(err as Error).message}`);
            }

            const exp = exported as Record<string, unknown>;
            const Candidate = (exp?.default ?? exported) as (new () => ServerModule) | undefined;

            if (typeof Candidate !== 'function') continue;
            if (!(Candidate.prototype instanceof ServerModule)) continue;

            let instance: ServerModule;
            try {
                instance = new Candidate();
            } catch (err: unknown) {
                throw new Error(`ModuleLoader: failed to instantiate ${Candidate.name || file}: ${(err as Error).message}`);
            }

            const schema = instance.envSchema();
            if (schema && Object.keys(schema).length > 0) {
                try {
                    EnvConfig.applySchema(schema);
                } catch (err: unknown) {
                    throw new Error(
                        `ModuleLoader: env schema validation failed for module "${instance.name}":\n${(err as Error).message}`
                    );
                }
            }

            log.info('Loading module', { name: instance.name });
            instance.register(ctx);

            // NOTE: Lifecycle is managed exclusively via loader.startAll() / stopAll().
            // Do NOT register addStartHook/addStopHook here — ctx.runStartHooks() is
            // never called in server.ts and the double-registration would invoke onStart
            // twice if runStartHooks() were ever added.

            this._modules.push(instance);
            log.info('Module loaded', { name: instance.name });
        }

        log.info('All modules loaded', { count: this._modules.length });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    async startAll(ctx: ServerContext): Promise<void> {
        for (const mod of this._modules) {
            try {
                await mod.onStart(ctx);
            } catch (err: unknown) {
                ctx.logger.forModule('ModuleLoader')
                    .error(`onStart failed for module "${mod.name}": ${(err as Error).message}`);
                throw err;
            }
        }
    }

    async stopAll(ctx: ServerContext): Promise<void> {
        const log = ctx.logger.forModule('ModuleLoader');
        for (const mod of [...this._modules].reverse()) {
            try {
                await mod.onStop(ctx);
            } catch (err: unknown) {
                log.error(`onStop failed for module "${mod.name}": ${(err as Error).message}`);
            }
        }
    }
}
