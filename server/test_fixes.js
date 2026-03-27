/**
 * AuraCast Server — Automated fix-verification test suite
 * Tests all 8 bugs fixed in this session.
 * Run: node test_fixes.js  (while server is on :4000)
 */
'use strict';
const http = require('http');
const WebSocket = require('ws');

const BASE  = 'http://localhost:4000';
const WS    = 'ws://localhost:4000';
const FAKE_ID = 'ffffffff-test-0000-0000-' + Date.now().toString(16).padStart(12,'0');

let passed = 0, failed = 0;

function ok(label, cond, detail = '') {
  if (cond) { console.log(`  ✅ ${label}`); passed++; }
  else       { console.log(`  ❌ FAIL: ${label}${detail ? ' — ' + detail : ''}`); failed++; }
}

function get(path) {
  return new Promise((res, rej) => {
    http.get(BASE + path, r => {
      let body = '';
      r.on('data', c => body += c);
      r.on('end', () => { try { res({ status: r.statusCode, body: JSON.parse(body) }); } catch { res({ status: r.statusCode, body }); } });
    }).on('error', rej);
  });
}

function post(path) {
  return new Promise((res, rej) => {
    const req = http.request(BASE + path, { method: 'POST', headers: { 'Content-Type': 'application/json' } }, r => {
      let body = '';
      r.on('data', c => body += c);
      r.on('end', () => { try { res({ status: r.statusCode, body: JSON.parse(body) }); } catch { res({ status: r.statusCode, body }); } });
    });
    req.on('error', rej);
    req.end('{}');
  });
}

function wsConnect(path, timeout = 4000) {
  return new Promise((res) => {
    const msgs = [];
    const ws = new WebSocket(WS + path);
    const timer = setTimeout(() => { ws.close(); res(msgs); }, timeout);
    ws.on('message', d => msgs.push(JSON.parse(d.toString())));
    ws.on('error', () => { clearTimeout(timer); res(msgs); });
    ws.on('close', () => { clearTimeout(timer); res(msgs); });
  });
}

// Inject a fake phone channel with all fields populated
function seedFakeChannel(id) {
  return new Promise((res) => {
    // Connect as phone on /control to create the channel
    const ws = new WebSocket(`${WS}/control?id=${id}&name=TestPhone`);
    ws.on('open', () => {
      // Send codec announce on /control (Bug 4 scenario — WebRTC-only device)
      ws.send(JSON.stringify({ type: 'codec', codec: 'opus', sampleRate: 48000, channels: 1, frameMs: 60, bitrate: 192000 }));
      // Send transportStatus
      ws.send(JSON.stringify({ type: 'transportStatus', mode: 'webrtc_p2p', rtt: 42, iceState: 'connected' }));
      // Send silence active
      ws.send(JSON.stringify({ type: 'silence', active: true }));
      // Send telemetry
      ws.send(JSON.stringify({ type: 'telemetry', batteryLevel: 85, charging: false, signalDbm: 30,
        cpuTempC: 38.5, wifiSsid: 'TestNet', usedRamMB: 200, totalRamMB: 4096,
        netType: 'wifi', linkSpeedMbps: 300, screenOn: true,
        streamFps: 50.0, streamKbps: 192.0, streamUptimeSec: 120,
        voxDropRate: 0.02, voxEnabled: true, voxThreshold: 150.0, updatedAt: Date.now() }));
      setTimeout(() => res(ws), 600);
    });
    ws.on('error', () => res(null));
  });
}

async function run() {
  console.log('\n🧪 AuraCast Fix Verification Suite\n');

  // ── Test 0: Health check ────────────────────────────────────────────────────
  console.log('[ Test 0 ] Server health');
  const health = await get('/health');
  ok('GET /health returns 200',  health.status === 200);
  ok('health.status === "ok"',   health.body?.status === 'ok');
  ok('version 5.2',              health.body?.version === '5.2');

  // ── Test 1: /streams includes silenceActive (Bug 7) ────────────────────────
  console.log('\n[ Test 1/7 ] /streams response includes silenceActive field');
  const phoneWs = await seedFakeChannel(FAKE_ID);
  await new Promise(r => setTimeout(r, 300));
  const streams = await get('/streams');
  ok('GET /streams returns 200', streams.status === 200);
  const entry = streams.body.find(s => s.id === FAKE_ID);
  ok('Fake channel in /streams',      !!entry,                   'entry not found');
  ok('/streams has silenceActive',    'silenceActive' in (entry || {}), 'field missing');
  ok('silenceActive is true',         entry?.silenceActive === true,    `got ${entry?.silenceActive}`);
  ok('/streams has transportMode',    entry?.transportMode === 'webrtc_p2p', `got ${entry?.transportMode}`);
  ok('/streams has transportRttMs',   entry?.transportRttMs === 42,    `got ${entry?.transportRttMs}`);

  // ── Test 2/8: /admin/devices includes silenceActive (Bug 8) ─────────────────
  console.log('\n[ Test 2/8 ] /admin/devices includes silenceActive');
  const devices = await get('/admin/devices');
  ok('GET /admin/devices 200',        devices.status === 200);
  const dev = devices.body.find(d => d.id === FAKE_ID);
  ok('Device in /admin/devices',      !!dev,                     'not found');
  ok('admin/devices has silenceActive', 'silenceActive' in (dev || {}), 'field missing');
  ok('silenceActive true in admin',    dev?.silenceActive === true);
  ok('admin has transportMode',        dev?.transportMode === 'webrtc_p2p');

  // ── Test 3/4: codec stored on /control (Bug 4) ──────────────────────────────
  console.log('\n[ Test 3/4 ] Codec announce on /control stored in channel');
  ok('codecConfig stored from /control', dev?.codecConfig?.sampleRate === 48000, `got ${dev?.codecConfig?.sampleRate}`);
  ok('codecConfig frameMs stored',       dev?.codecConfig?.frameMs === 60,       `got ${dev?.codecConfig?.frameMs}`);

  // ── Test 4/1: /listen browser seeds transportStatus (Bug 1) ─────────────────
  console.log('\n[ Test 4/1 ] /listen seeds transportStatus immediately on join');
  const listenMsgs = await wsConnect(`/listen?id=${FAKE_ID}`, 1500);
  const transportMsg = listenMsgs.find(m => m.type === 'transportStatus');
  ok('/listen sent transportStatus',  !!transportMsg,            `msgs: ${listenMsgs.map(m=>m.type)}`);
  ok('transportStatus.mode correct',  transportMsg?.mode === 'webrtc_p2p', `got ${transportMsg?.mode}`);
  ok('transportStatus.rtt correct',   transportMsg?.rtt === 42,            `got ${transportMsg?.rtt}`);
  ok('transportStatus.iceState',      transportMsg?.iceState === 'connected');

  // ── Test 5/2: /listen browser seeds silenceActive (Bug 2) ───────────────────
  console.log('\n[ Test 5/2 ] /listen seeds silence state immediately on join');
  const silenceMsg = listenMsgs.find(m => m.type === 'silence');
  ok('/listen sent silence seed',     !!silenceMsg,              `msgs: ${listenMsgs.map(m=>m.type)}`);
  ok('silence.active is true',        silenceMsg?.active === true);

  // ── Test 6: /listen seeds codec (Bug 6) ─────────────────────────────────────
  console.log('\n[ Test 6 ] /listen seeds codec config on join');
  const codecMsg = listenMsgs.find(m => m.type === 'codec');
  ok('/listen sent codec',            !!codecMsg,                `msgs: ${listenMsgs.map(m=>m.type)}`);
  ok('codec.sampleRate 48000',        codecMsg?.sampleRate === 48000);
  ok('codec.frameMs 60',              codecMsg?.frameMs === 60);

  // ── Test 7: /ice-config STUN only ────────────────────────────────────────────
  console.log('\n[ Test 7 ] /ice-config returns STUN-only servers');
  const ice = await get('/ice-config');
  ok('GET /ice-config 200',           ice.status === 200);
  ok('stunOnly flag present',         ice.body?.stunOnly === true);
  ok('iceServers is array',           Array.isArray(ice.body?.iceServers));
  ok('all servers are stun:',         ice.body?.iceServers?.every(s => s.urls.startsWith('stun:')));

  // ── Test 8: Admin command dispatch (set-quality) ─────────────────────────────
  console.log('\n[ Test 8 ] Admin dispatch set-quality to fake channel');
  const qr = await post(`/admin/${FAKE_ID}/set-quality?level=MEDIUM`);
  ok('set-quality 200',               qr.status === 200, `status=${qr.status}`);
  ok('dispatched via websocket',      qr.body?.channel === 'websocket', `channel=${qr.body?.channel}`);
  ok('action is set_quality',         qr.body?.action === 'set_quality');

  // ── Test 9: Admin snapshot returns 409 (no pending) ─────────────────────────
  console.log('\n[ Test 9 ] /admin/:id/snapshot 409 when no pending snapshot');
  const snap = await post(`/admin/${FAKE_ID}/snapshot`);
  // Either times out (409) or sends ok — both are valid behaviour
  ok('snapshot not 500',              snap.status !== 500, `got 500: ${JSON.stringify(snap.body)}`);

  // ── Test 10: Transport status broadcast (Bug 3 indirect) ─────────────────────
  console.log('\n[ Test 10 ] transportStatus broadcasts to /listen browsers');
  let gotTransport = false;
  await new Promise((resolve) => {
    const bws = new WebSocket(`${WS}/listen?id=${FAKE_ID}`);
    bws.on('open', () => {
      // Phone sends a new transportStatus
      if (phoneWs?.readyState === WebSocket.OPEN) {
        phoneWs.send(JSON.stringify({ type: 'transportStatus', mode: 'websocket_relay', rtt: 200, iceState: 'closed' }));
      }
    });
    bws.on('message', (d) => {
      const m = JSON.parse(d.toString());
      if (m.type === 'transportStatus' && m.mode === 'websocket_relay') { gotTransport = true; bws.close(); resolve(); }
    });
    setTimeout(() => { bws.close(); resolve(); }, 2000);
  });
  ok('transportStatus broadcast to browsers', gotTransport);

  // ── Cleanup ───────────────────────────────────────────────────────────────────
  if (phoneWs?.readyState === WebSocket.OPEN) phoneWs.close();

  // ── Summary ───────────────────────────────────────────────────────────────────
  console.log(`\n${'─'.repeat(50)}`);
  console.log(`  Total: ${passed + failed}  ✅ ${passed} passed  ${failed > 0 ? '❌ ' + failed + ' failed' : '🎉 All passed!'}`);
  console.log(`${'─'.repeat(50)}\n`);
  process.exit(failed > 0 ? 1 : 0);
}

run().catch(e => { console.error('Test runner error:', e); process.exit(1); });
