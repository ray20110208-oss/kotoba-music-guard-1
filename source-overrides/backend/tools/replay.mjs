// Replay unfiltered raw PCM16/24kHz/mono in real time. NDJSON output stays local.
import {open} from 'node:fs/promises';
import {once} from 'node:events';
import {setTimeout as delay} from 'node:timers/promises';
import WebSocket from 'ws';
import {HttpsProxyAgent} from 'https-proxy-agent';
const [path] = process.argv.slice(2);
const endpoint = process.env.KOTOBA_RELAY_URL;
const token = process.env.KOTOBA_APP_TOKEN;
if (!path || !endpoint?.startsWith('wss://') || !token) {
  console.error('Usage: KOTOBA_RELAY_URL=wss://host/v1/live KOTOBA_APP_TOKEN=<env secret> node tools/replay.mjs original.pcm');
  process.exit(1);
}
const file = await open(path, 'r');
if ((await file.stat()).size % 2) throw new Error('PCM16 requires an even byte count');
const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
const ws = new WebSocket(endpoint, {agent: proxy ? new HttpsProxyAgent(proxy) : undefined, headers: {Authorization: `Bearer ${token}`}, handshakeTimeout: 10000});
let ready = false, start = 0, frames = 0, failed = false, ending = false;
const setupDeadline = setTimeout(() => ws.terminate(), 10000);
ws.on('error', () => { failed = true; console.error('relay_connection_failed'); });
ws.on('message', raw => {
  const e = JSON.parse(raw.toString());
  if (e.type === 'ready') {ready = true; clearTimeout(setupDeadline); start = performance.now();}
  if (e.type === 'caption' || e.type === 'usage' || e.type === 'status')
    console.log(JSON.stringify({...e, receivedMs: Math.round(performance.now() - start),
      // Window-end age is NOT semantic subtitle latency; bilingual timestamped review is still required.
      windowEndAgeMs: e.audioEndMs === undefined ? undefined : Math.round(performance.now() - start - e.audioEndMs)}));
});
ws.on('close', () => { if (!ending) failed = true; });
try {
  await once(ws, 'open');
  while (!ready && ws.readyState === WebSocket.OPEN) await delay(20);
  if (!ready) throw new Error('relay_not_ready');
  while (ws.readyState === WebSocket.OPEN) {
    const buffer = Buffer.alloc(9600);
    const {bytesRead} = await file.read(buffer, 0, buffer.length, null);
    if (!bytesRead) break;
    if (ws.bufferedAmount > 48000) throw new Error('replay_network_backlog');
    ws.send(buffer); // Final short frame is padded with silence, never VAD-filtered.
    frames++;
    await delay(Math.max(0, start + frames * 200 - performance.now()));
  }
  // Give the final partial translation window real silence, then wait for bounded output.
  for (let i = 0; i < 15 && ws.readyState === WebSocket.OPEN; i++) {
    ws.send(Buffer.alloc(9600)); frames++;
    await delay(Math.max(0, start + frames * 200 - performance.now()));
  }
  await delay(3000);
  ending = true;
  ws.close();
} finally {clearTimeout(setupDeadline); await file.close(); ws.terminate();}
if (failed) process.exitCode = 1;
