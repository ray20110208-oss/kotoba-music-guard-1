import {createServer} from 'node:http';
import {timingSafeEqual} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {WebSocketServer} from 'ws';
import {HttpsProxyAgent} from 'https-proxy-agent';
import {bridge} from './relay.mjs';

export function createRelayServer({apiKey, appToken,
  model = 'gpt-realtime-2.1-mini', maxConnections = 1,
  upstreamUrl = `wss://api.openai.com/v1/realtime?model=${encodeURIComponent(model)}`,
  ...options} = {}) {
  const server = createServer((req, res) => {
    res.writeHead(req.url === '/health' ? 200 : 404, {'content-type': 'application/json', 'cache-control': 'no-store'});
    res.end(JSON.stringify(req.url === '/health'
      ? {ok: true, apiConfigured: Boolean(apiKey), authConfigured: Boolean(appToken)} : {error: 'not_found'}));
  });
  const wss = new WebSocketServer({noServer: true, maxPayload: 9600, perMessageDeflate: false});
  const sessions = new Set();
  server.on('upgrade', (req, socket, head) => {
    const reject = code => { socket.end(`HTTP/1.1 ${code}\r\nConnection: close\r\n\r\n`); };
    if (req.url !== '/v1/live') return reject('404 Not Found');
    if (!apiKey || !appToken || appToken.length < 32) return reject('503 Service Unavailable');
    const actual = Buffer.from(req.headers.authorization || '');
    const expected = Buffer.from(`Bearer ${appToken}`);
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return reject('401 Unauthorized');
    if (wss.clients.size >= maxConnections) return reject('429 Too Many Requests');
    wss.handleUpgrade(req, socket, head, client => {
      const session = bridge(client, {apiKey, model, upstreamUrl, ...options});
      sessions.add(session); client.once('close', () => sessions.delete(session));
    });
  });
  return {server, close: async () => {
    for (const session of sessions) session.close();
    wss.close();
    await new Promise(resolve => server.close(resolve));
  }};
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
  const {server} = createRelayServer({apiKey: process.env.OPENAI_API_KEY,
    agent: proxy ? new HttpsProxyAgent(proxy) : undefined,
    appToken: process.env.KOTOBA_APP_TOKEN, model: process.env.KOTOBA_REALTIME_MODEL || 'gpt-realtime-2.1-mini'});
  server.listen(Number(process.env.PORT || 8787), '127.0.0.1', () => console.log('Kotoba relay listening on loopback'));
}
