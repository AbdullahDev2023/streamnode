/**
 * services/metrics.ts — In-process metrics accumulator.
 */

const startedAt = Date.now();

type CounterKey = 'httpRequests' | 'wsConnections' | 'audioFrames' | 'commandsSent' | 'rateLimitHits' | 'errors';
type GaugeKey   = 'activeChannels' | 'activeBrowsers';

const counters: Record<CounterKey, number> = {
    httpRequests:  0,
    wsConnections: 0,
    audioFrames:   0,
    commandsSent:  0,
    rateLimitHits: 0,
    errors:        0,
};

const gauges: Record<GaugeKey, number> = {
    activeChannels: 0,
    activeBrowsers: 0,
};

export function increment(name: CounterKey, amount = 1): void {
    if (name in counters) counters[name] += amount;
}

export function gauge(name: GaugeKey, value: number): void {
    if (name in gauges) gauges[name] = value;
}

export function snapshot(): object {
    return {
        uptimeSec: Math.floor((Date.now() - startedAt) / 1000),
        counters:  { ...counters },
        gauges:    { ...gauges },
    };
}

export function toPrometheus(): string {
    const lines: string[] = [];
    const up = Math.floor((Date.now() - startedAt) / 1000);
    lines.push(`# HELP auracast_uptime_seconds Server uptime in seconds`);
    lines.push(`# TYPE auracast_uptime_seconds gauge`);
    lines.push(`auracast_uptime_seconds ${up}`);
    for (const [k, v] of Object.entries(counters)) {
        lines.push(`# TYPE auracast_${k}_total counter`);
        lines.push(`auracast_${k}_total ${v}`);
    }
    for (const [k, v] of Object.entries(gauges)) {
        lines.push(`# TYPE auracast_${k} gauge`);
        lines.push(`auracast_${k} ${v}`);
    }
    return lines.join('\n') + '\n';
}
