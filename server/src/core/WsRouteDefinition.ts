/**
 * core/WsRouteDefinition.ts
 */
import type WebSocket from 'ws';
import type { IncomingMessage } from 'http';
import type { ServerContext } from './ServerContext';

type WsHandler = (ws: WebSocket, req: IncomingMessage, ctx: ServerContext) => void;

export class WsRouteDefinition {
    readonly routePath: string | RegExp;
    readonly handler:   WsHandler;

    constructor(routePath: string | RegExp, handler: WsHandler) {
        if (typeof handler !== 'function') {
            throw new TypeError(`WsRouteDefinition: handler must be a function (path: ${routePath})`);
        }
        this.routePath = routePath;
        this.handler   = handler;
    }

    matches(pathname: string): boolean {
        if (this.routePath === '*') return true;
        if (this.routePath instanceof RegExp) return this.routePath.test(pathname);
        return this.routePath === pathname;
    }
}
