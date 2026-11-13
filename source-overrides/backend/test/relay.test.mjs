import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import WebSocket, {WebSocketServer} from 'ws';
import {createRelayServer} from '../src/server.mjs';

const token = 'test-only-token-with-at-least-32-characters';
const next = (ws, predicate) => new Promise((resolve, reject) => {
  const timeout = setTimeout(() => {ws.off('message', listener); reject(new Error('event timeout'));}, 2000);
  function listener(raw) {
    const e = JSON.parse(raw.toString());
    if (predicate(e)) {clearTimeout(timeout); ws.off('message', listener); resolve(e);}
  }
  ws.on('message', listener);
});
async function fixture(t, opts = {}) {
  let clock = 100000;
  const mock = new WebSocketServer({port: 0, host: '127.0.0.1'});
  await once(mock, 'listening');
  const relay = createRelayServer({apiKey: 'fake-test-key', appToken: token,
    upstreamUrl: `ws://127.0.0.1:${mock.address().port}`, now: () => clock, tickMs: 5, ...opts});
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  const connected = once(mock, 'connection');
  const client = new WebSocket(`ws://127.0.0.1:${relay.server.address().port}/v1/live`, {headers: {Authorization: `Bearer ${token}`}});
  client.on('error', () => {});
  const [[provider]] = await Promise.all([connected, once(client, 'open')]);
  provider.on('error', () => {});
  const events = []; provider.on('message', raw => events.push(JSON.parse(raw.toString())));
  t.after(async () => {client.terminate(); provider.terminate(); await relay.close(); await new Promise(r => mock.close(r));});
  const config = next(provider, e => e.type === 'session.update');
  provider.send(JSON.stringify({type: 'session.created'}));
  const setup = await config;
  const ready = next(client, e => e.type === 'ready');
  provider.send(JSON.stringify({type: 'session.updated'})); await ready;
  async function frames(count) {
    for (let i = 0; i < count; i++) {
      clock += 200;
      const received = next(provider, e => e.type === 'input_audio_buffer.append');
      client.send(Buffer.alloc(9600)); await received;
    }
  }
  return {client, provider, setup, events, frames, advance: ms => {clock += ms;}};
}

test('continuous raw audio, manual trigger, one response, stable caption IDs and bounded context', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.setup.session.output_modalities, ['text']);
  assert.equal(f.setup.session.audio.input.turn_detection, null);
  assert.equal(f.setup.session.audio.input.transcription, undefined);
  for (let i = 0; i < 12; i++) {
    const create = next(f.provider, e => e.type === 'response.create');
    await f.frames(15); await create;
    f.provider.send(JSON.stringify({type: 'input_audio_buffer.committed', item_id: `audio${i}`}));
    f.provider.send(JSON.stringify({type: 'response.created', response: {id: `r${i}`}}));
    const caption = next(f.client, e => e.type === 'caption' && !e.final);
    f.provider.send(JSON.stringify({type: 'response.output_text.delta', response_id: `r${i}`, delta: '今天一起玩遊戲。'}));
    const c = await caption;
    assert.equal(c.id, i + 1); assert.equal(c.audioEndMs, (i + 1) * 3000);
    const done = next(f.client, e => e.type === 'caption' && e.final);
    f.provider.send(JSON.stringify({type: 'response.done', response: {id: `r${i}`, status: 'completed', output: [{id: `text${i}`}], usage: {total_tokens: 10}}}));
    assert.equal((await done).text, '今天一起玩遊戲。');
  }
  await new Promise(r => setTimeout(r, 10));
  assert.equal(f.events.filter(e => e.type === 'response.create').length, 12);
  assert.equal(f.events.filter(e => e.type === 'conversation.item.delete').length, 4);
});

test('slow response never creates a concurrent request; deadline closes upstream', async t => {
  const f = await fixture(t, {responseMs: 6000});
  const create = next(f.provider, e => e.type === 'response.create');
  await f.frames(15); await create;
  await f.frames(15);
  assert.equal(f.events.filter(e => e.type === 'response.create').length, 1);
  const status = next(f.client, e => e.type === 'status');
  const closed = once(f.provider, 'close');
  f.advance(3100);
  assert.equal((await status).state, 'response_timeout'); await closed;
});

test('stop tears down upstream and cancels unfinished output', async t => {
  const f = await fixture(t);
  const closed = once(f.provider, 'close');
  f.client.send('{"type":"stop"}'); await closed;
});

test('rejects invalid PCM and faster-than-realtime uploads', async t => {
  const f = await fixture(t);
  const status = next(f.client, e => e.type === 'status');
  f.client.send(Buffer.alloc(3));
  assert.equal((await status).state, 'invalid_pcm');
});

test('limits burst rate', async t => {
  const f = await fixture(t);
  const status = next(f.client, e => e.type === 'status');
  for (let i = 0; i < 8; i++) f.client.send(Buffer.alloc(9600));
  assert.equal((await status).state, 'audio_rate_exceeded');
});

test('missing credentials fail closed and invalid tokens cannot open upstream', async t => {
  for (const [config, expected] of [[{},503], [{apiKey:'fake', appToken:token},401]]) {
    const relay = createRelayServer(config);
    relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
    const client = new WebSocket(`ws://127.0.0.1:${relay.server.address().port}/v1/live`);
    client.on('error', () => {});
    const [, response] = await once(client, 'unexpected-response');
    assert.equal(response.statusCode, expected); response.resume(); client.terminate(); await relay.close();
  }
});

test('default response deadline rejects stale work after three seconds', async t => {
  const f = await fixture(t);
  const create = next(f.provider, e => e.type === 'response.create');
  await f.frames(15); await create;
  const status = next(f.client, e => e.type === 'status');
  f.advance(3001);
  assert.equal((await status).state, 'response_timeout');
});

test('session rotation closes both ends instead of accumulating audio', async t => {
  const f = await fixture(t, {rotateMs: 1000});
  const status = next(f.client, e => e.type === 'status');
  const closed = once(f.provider, 'close');
  f.advance(1001);
  assert.equal((await status).state, 'rotate_session'); await closed;
});

test('audio backlog is bounded even if response timeout is increased', async t => {
  const f = await fixture(t, {responseMs: 20000});
  const create = next(f.provider, e => e.type === 'response.create');
  await f.frames(15); await create;
  await f.frames(30);
  const status = next(f.client, e => e.type === 'status');
  f.advance(200); f.client.send(Buffer.alloc(9600));
  assert.equal((await status).state, 'audio_backlog');
});
