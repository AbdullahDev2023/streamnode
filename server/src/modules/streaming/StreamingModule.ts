/**
 * modules/streaming/StreamingModule.ts
 *
 * Wraps the existing AuraCast HTTP and WebSocket route handlers as a proper
 * ServerModule.  Registers:
 *
 *   HTTP routes  — all paths via routes/http.ts (wildcard catch-all)
 *   WS routes    — /stream, /control, /listen via routes/websocket.ts
 *   Health check — streaming-channels (live phone + channel counts)
 */
import {
    ServerModule,
    HttpRouteDefinition,
    WsRouteDefinition,
    HealthCheck,
} from '../../core';
import type { ServerContext } from '../../core/ServerContext';

import { handler as httpHandler } from '../../routes/http';
import { handler as wsHandler   } from '../../routes/websocket';
import * as channels from '../../channels';

export default class StreamingModule extends ServerModule {
    get name(): string { return 'Streaming'; }

    register(ctx: ServerContext): void {
        ctx.addHttpRoute(
            new HttpRouteDefinition('*', '*', (req, res) => httpHandler(req, res))
        );

        ctx.addWsRoute(
            new WsRouteDefinition('*', (ws, req) => wsHandler(ws, req))
        );

        ctx.addHealthCheck(
            new HealthCheck('streaming-channels', async () => {
                let livePhones = 0;
                let streaming  = 0;
                for (const ch of channels.streams.values()) {
                    if (channels.isPhoneConnected(ch)) livePhones++;
                    if (ch.streamingActive)            streaming++;
                }
                return {
                    status:        'ok' as const,
                    totalChannels: channels.streams.size,
                    livePhones,
                    streaming,
                };
            })
        );
    }
}
