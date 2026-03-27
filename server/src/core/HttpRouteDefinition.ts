/**
 * core/HttpRouteDefinition.ts
 */
import type { IncomingMessage, ServerResponse } from 'http';
import type { ServerContext } from './ServerContext';

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'HEAD' | 'OPTIONS' | '*';
type HttpHandler = (req: IncomingMessage, res: ServerResponse, ctx: ServerContext) => Promise<void> | void;

export class HttpRouteDefinition {
    readonly method:    string;
    readonly routePath: string | RegExp;
    readonly handler:   HttpHandler;

    constructor(method: HttpMethod | string, routePath: string | RegExp, handler: HttpHandler) {
        if (typeof handler !== 'function') {
            throw new TypeError(`HttpRouteDefinition: handler must be a function (route: ${method} ${routePath})`);
        }
        this.method    = typeof method === 'string' ? method.toUpperCase() : method;
        this.routePath = routePath;
        this.handler   = handler;
    }

    matches(method: string, pathname: string): boolean {
        const methodOk = this.method === '*' || this.method === method;
        if (!methodOk) return false;
        if (this.routePath === '*') return true;
        if (this.routePath instanceof RegExp) return this.routePath.test(pathname);
        return this.routePath === pathname;
    }
}
