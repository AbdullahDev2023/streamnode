/**
 * core/ServerModule.ts — Abstract base class for all AuraCast server modules.
 */
import type { ServerContext } from './ServerContext';
import type { EnvFieldSchema } from './EnvConfig';

export abstract class ServerModule {
    /** Human-readable module name. Subclasses MUST override. */
    abstract get name(): string;

    /** Declare env variables this module needs. Optional. */
    envSchema(): Record<string, EnvFieldSchema> {
        return {};
    }

    /**
     * Register routes, WS handlers, and health checks into ctx.
     * Subclasses MUST override.
     */
    abstract register(ctx: ServerContext): void;

    /** Called after the HTTP server starts listening. Optional. */
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    async onStart(_ctx: ServerContext): Promise<void> {}

    /** Called during graceful shutdown. Optional. */
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    async onStop(_ctx: ServerContext): Promise<void> {}
}
