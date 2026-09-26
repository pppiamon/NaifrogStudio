import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID, randomBytes } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import sharp from 'sharp';
import { CodexManager, runImageTurn } from '../src/codex-bridge.mjs';
import { createService } from '../src/server.mjs';
import { PROMPT } from '../src/image-pipeline.mjs';

class FakeRpc extends EventEmitter {
  constructor() { super(); this.calls = []; this.loggedIn = false; this.closed = false; }
  async initialize() { return this; }
  async call(method, params) {
    this.calls.push({ method, params });
    if (method === 'account/login/start') return { type: 'chatgptDeviceCode', loginId: 'login-1', verificationUrl: 'https://auth.openai.com/codex/device', userCode: 'TEST-1234' };
    if (method === 'account/read') return { account: this.loggedIn ? { type: 'chatgpt', email: 'fixture@example.com', planType: 'plus' } : null };
    if (method === 'account/logout') this.loggedIn = false;
    if (method === 'thread/start') return { thread: { id: 'thread-1' } };
    if (method === 'turn/start') {
      if (this.onTurn) await this.onTurn(params);
      return { turn: { id: 'turn-1' } };
    }
    return {};
  }
  complete(items = [], status = 'completed') {
    for (const item of items) this.emit('notification', { method: 'item/completed', params: { threadId: 'thread-1', item } });
    this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread-1', turn: { id: 'turn-1', status, items } } });
  }
  close() { this.closed = true; this.emit('closed', new Error('closed')); }
}

test('device login, account completion, logout and independent session storage', async t => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'naifrog-codex-'));
  const runtimes = [], homes = [];
  const manager = new CodexManager({ dataDir, rpcFactory: home => { homes.push(home); const rpc = new FakeRpc(); runtimes.push(rpc); return rpc; } });
  t.after(async () => { manager.close(); await rm(dataDir, { recursive: true, force: true }); });
  const first = await manager.startLogin('');
  assert.match(first.sessionToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(first.state, 'pending'); assert.equal(first.userCode, 'TEST-1234');
  assert.equal((await manager.startLogin(first.sessionToken)).loginId, first.loginId);
  assert.equal(runtimes[0].calls.filter(c => c.method === 'account/login/start').length, 1);
  const second = await manager.startLogin('');
  assert.notEqual(first.sessionToken, second.sessionToken); assert.notEqual(homes[0], homes[1]);
  runtimes[0].loggedIn = true;
  runtimes[0].emit('notification', { method: 'account/login/completed', params: { loginId: 'login-1', success: true } });
  assert.equal((await manager.account(first.sessionToken)).authenticated, true);
  assert.equal((await manager.account(second.sessionToken)).authenticated, false);
  assert.equal((await manager.logout(first.sessionToken)).authenticated, false);
  assert.equal((await manager.account(first.sessionToken)).authenticated, false);
});

test('completion events emitted before turn/start response still return the image', async () => {
  const rpc = new FakeRpc();
  const item = { id: 'img', type: 'imageGeneration', status: 'completed', result: 'aGVsbG8=' };
  rpc.onTurn = () => rpc.complete([item]);
  assert.deepEqual(await runImageTurn(rpc, 'thread-1', [], new AbortController().signal), item);
  assert.equal(rpc.listenerCount('notification'), 0);
});

test('text-only and failed turns never count as generated pictures', async () => {
  const rpc = new FakeRpc(); rpc.onTurn = () => rpc.complete([{ type: 'agentMessage', text: '当前额度已用完' }]);
  await assert.rejects(() => runImageTurn(rpc, 'thread-1', []), /额度/);
  rpc.onTurn = () => rpc.complete([], 'failed');
  await assert.rejects(() => runImageTurn(rpc, 'thread-1', []), /未完成/);
});

test('abort interrupts the active Codex turn and removes listeners', async () => {
  const rpc = new FakeRpc(), controller = new AbortController();
  const pending = runImageTurn(rpc, 'thread-1', [], controller.signal);
  await delay(1); controller.abort();
  await assert.rejects(() => pending, /取消/);
  assert.ok(rpc.calls.some(c => c.method === 'turn/interrupt' && c.params.turnId === 'turn-1'));
  assert.equal(rpc.listenerCount('notification'), 0);
});

test('Codex image turn preserves references, prompt, shape and unselected pixels', async t => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'naifrog-codex-image-'));
  const rpc = new FakeRpc();
  const manager = new CodexManager({ dataDir, rpcFactory: () => rpc });
  t.after(async () => { manager.close(); await rm(dataDir, { recursive: true, force: true }); });
  const login = await manager.startLogin(''); rpc.loggedIn = true;
  const pixels = Buffer.alloc(128 * 128 * 4); for (let i = 0; i < pixels.length; i += 4) pixels.set([255, 255, 255, i / 4 < 128 * 64 ? 255 : 0], i);
  const encode = async buffer => (await sharp(buffer, { raw: { width: 128, height: 128, channels: 4 } }).png().toBuffer()).toString('base64');
  const original = await sharp({ create: { width: 128, height: 128, channels: 4, background: '#123456' } }).png().toBuffer();
  const generated = await sharp({ create: { width: 1024, height: 1024, channels: 4, background: '#ff0000' } }).png().toBuffer();
  rpc.onTurn = async ({ input }) => {
    assert.ok(input[0].text.startsWith(PROMPT)); assert.match(input[0].text, /image_generation/);
    assert.match(input[0].text, /拟人化角色/);
    assert.deepEqual(input.slice(1).map(i => path.basename(i.path)), ['01-original.png', '02-naifrog-reference.png', '03-selection-mask.png']);
    assert.deepEqual(await readFile(input[2].path), original);
    rpc.complete([{ type: 'imageGeneration', status: 'completed', result: generated.toString('base64') }]);
  };
  const output = await manager.edit({ requestId: randomUUID(), original: original.toString('base64'), reference: original.toString('base64'), selection: await encode(pixels) }, login.sessionToken, new AbortController().signal);
  const { data, info } = await sharp(output).raw().toBuffer({ resolveWithObject: true });
  assert.equal(info.width, 128); assert.equal(info.height, 128);
  assert.deepEqual([...data.subarray(0, 4)], [255, 0, 0, 255]);
  for (let i = 128 * 64 * 4; i < data.length; i += 4) assert.deepEqual([...data.subarray(i, i + 4)], [18, 52, 86, 255]);
});

test('Codex HTTP login/jobs bind results and cancellation to the signed-in session', async t => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'naifrog-codex-http-'));
  let calls = 0;
  const codexManager = { startLogin: async () => ({ sessionToken: randomBytes(32).toString('base64url'), userCode: 'CODE', verificationUrl: 'https://auth.openai.com/codex/device' }),
    account: async token => { await delay(15); return { authenticated: !!token }; }, logout: async () => ({ authenticated: false }),
    edit: async () => { calls++; await delay(100); return Buffer.from('codex-png'); }, close() {} };
  const server = await createService({ dataDir, codexEnabled: true, codexManager });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const login = await (await fetch(base + '/v1/codex/login', { method: 'POST' })).json();
  const headers = { 'X-Codex-Session': login.sessionToken };
  const id = randomUUID(), body = JSON.stringify({ requestId: id, provider: 'codex', original: 'a', reference: 'b', selection: 'c' });
  assert.equal((await fetch(base + '/v1/jobs', { method: 'POST', body })).status, 401);
  const duplicate = await Promise.all([1, 2].map(() => fetch(base + '/v1/jobs', { method: 'POST', headers, body })));
  assert.deepEqual(duplicate.map(r => r.status).sort(), [200, 202]);
  assert.equal((await fetch(base + '/v1/codex/logout', { method: 'POST', headers })).status, 409);
  assert.equal((await fetch(base + '/v1/jobs/' + id, { headers: { 'X-Codex-Session': randomBytes(32).toString('base64url') } })).status, 404);
  assert.equal((await fetch(base + '/v1/jobs/' + id + '/cancel', { method: 'POST' })).status, 404);
  await delay(160);
  assert.equal((await fetch(base + '/v1/jobs/' + id + '/result')).status, 404);
  assert.equal(await (await fetch(base + '/v1/jobs/' + id + '/result', { headers })).text(), 'codex-png');
  assert.equal(calls, 1);
  assert.equal((await fetch(base + '/v1/codex/logout', { method: 'POST', headers })).status, 200);
});
