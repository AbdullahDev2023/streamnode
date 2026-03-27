/**
 * src/types/ws.d.ts — Ambient declarations for the 'ws' package.
 * Matches ws@8.x API used throughout AuraCast server.
 */
/// <reference types="node" />

import { EventEmitter } from 'events';
import { IncomingMessage, Server as HttpServer } from 'http';
import { Server as HttpsServer } from 'https';

declare module 'ws' {

    type Data = string | Buffer | ArrayBuffer | Buffer[];

    interface SendOptions {
        mask?: boolean;
        binary?: boolean;
        compress?: boolean;
        fin?: boolean;
    }

    class WebSocket extends EventEmitter {
        static readonly CONNECTING: 0;
        static readonly OPEN:       1;
        static readonly CLOSING:    2;
        static readonly CLOSED:     3;

        static Server: typeof Server;

        readonly readyState: 0 | 1 | 2 | 3;
        readonly url: string;

        /** Custom field used in heartbeat logic */
        isAlive?: boolean;

        constructor(address: string, options?: Record<string, unknown>);

        send(data: string | Buffer | ArrayBuffer | Buffer[], options?: SendOptions, cb?: (err?: Error) => void): void;
        send(data: string | Buffer | ArrayBuffer | Buffer[], cb?: (err?: Error) => void): void;
        ping(data?: string | Buffer, mask?: boolean, cb?: (err: Error) => void): void;
        terminate(): void;
        close(code?: number, reason?: string | Buffer): void;

        on(event: 'open',    listener: () => void): this;
        on(event: 'close',   listener: (code: number, reason: Buffer) => void): this;
        on(event: 'error',   listener: (err: Error) => void): this;
        on(event: 'message', listener: (data: Data, isBinary: boolean) => void): this;
        on(event: 'ping',    listener: (data: Buffer) => void): this;
        on(event: 'pong',    listener: (data: Buffer) => void): this;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        on(event: string,    listener: (...args: any[]) => void): this;
    }

    interface ServerOptions {
        server?:         HttpServer | HttpsServer;
        port?:           number;
        host?:           string;
        path?:           string;
        noServer?:       boolean;
        clientTracking?: boolean;
        maxPayload?:     number;
    }

    class Server extends EventEmitter {
        readonly clients: Set<WebSocket>;

        constructor(options?: ServerOptions, callback?: () => void);

        on(event: 'connection', listener: (ws: WebSocket, req: IncomingMessage) => void): this;
        on(event: 'error',      listener: (err: Error) => void): this;
        on(event: 'listening',  listener: () => void): this;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        on(event: string,       listener: (...args: any[]) => void): this;

        close(cb?: (err?: Error) => void): void;
    }

    export { WebSocket, WebSocket as default, Server };
}
