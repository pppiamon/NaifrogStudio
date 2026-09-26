import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import vm from 'node:vm';
import { createService } from '../src/server.mjs';
import { PAIR_PAGE } from '../src/join-page.mjs';
import { pairingCode } from '../src/connect.mjs';

async function fixture(t, options = {}) {
  const root = await mkdtemp(path.join(tmpdir(), 'naifrog-sharing-'));
  const apkPath = path.join(root, 'release.apk');
  const server = await createService({ dataDir: root, appToken: 'shared-entry', apkPath, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true, force: true }); });
  return { server, apkPath, url: `http://127.0.0.1:${server.address().port}` };
}

test('invitation page and APK can be fetched before login, including HEAD and later APK replacement', async t => {
  const { url, apkPath } = await fixture(t);
  assert.equal((await fetch(url + '/pair')).status, 200);
  assert.equal((await fetch(url + '/download/naifrog.apk')).status, 404);
  await writeFile(apkPath, 'first-apk');
  const head = await fetch(url + '/download/naifrog.apk', { method: 'HEAD' });
  assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), '9'); assert.equal(await head.text(), '');
  assert.equal(await (await fetch(url + '/download/naifrog.apk')).text(), 'first-apk');
  await writeFile(apkPath, 'new-apk');
  assert.equal(await (await fetch(url + '/download/naifrog.apk')).text(), 'new-apk');
  assert.equal((await fetch(url + '/v1/status')).status, 401);
});

test('two invited users obtain independent sessions and retries keep each login stable', async t => {
  const sessions = new Map();
  const manager = {
    close() {},
    async startLogin() { const token = randomBytes(32).toString('base64url'); sessions.set(token, true); return { sessionToken: token, userCode: randomUUID() }; },
    async account(token) { return { authenticated: sessions.get(token) === true }; },
    async logout(token) { sessions.set(token, false); return { authenticated: false }; }
  };
  const { url } = await fixture(t, { codexEnabled: true, codexManager: manager });
  const requests = [randomUUID(), randomUUID()];
  const login = id => fetch(url + '/v1/codex/login', { method: 'POST', headers: { Authorization: 'Bearer shared-entry', 'X-Request-Id': id } }).then(r => r.json());
  const [one, two, retryOne] = await Promise.all([login(requests[0]), login(requests[1]), login(requests[0])]);
  assert.notEqual(one.sessionToken, two.sessionToken); assert.notEqual(one.userCode, two.userCode);
  assert.equal(one.sessionToken, retryOne.sessionToken); assert.equal(sessions.size, 2);
  const headers = token => ({ Authorization: 'Bearer shared-entry', 'X-Codex-Session': token });
  await fetch(url + '/v1/codex/logout', { method: 'POST', headers: headers(one.sessionToken) });
  assert.equal((await (await fetch(url + '/v1/codex/account', { headers: headers(two.sessionToken) })).json()).authenticated, true);
  assert.equal((await (await fetch(url + '/v1/codex/account', { headers: headers(one.sessionToken) })).json()).authenticated, false);
});

test('invitation page builds the app deep link, checks identity and handles malformed fragments', async () => {
  const identity = randomUUID();
  const code = pairingCode({ version: 1, serviceId: identity, endpoints: ['https://shared.example'], token: 'fixture' });
  const run = async (hash, serviceId = identity) => {
    const elements = new Map();
    const context = {
      document: { getElementById(id) { if (!elements.has(id)) elements.set(id, {}); return elements.get(id); } },
      location: { hash }, navigator: { clipboard: { writeText: async () => {} } },
      TextDecoder, Uint8Array, atob, AbortSignal,
      fetch: async url => ({ ok: true, json: async () => ({ serviceId, ready: true }) })
    };
    await vm.runInNewContext(PAIR_PAGE.match(/<script>([\s\S]*)<\/script>/)[1], context);
    return elements;
  };
  const valid = await run('#' + code);
  assert.equal(valid.get('open').href, 'naifrog://connect?code=' + encodeURIComponent(code));
  assert.match(valid.get('status').textContent, /服务在线/);
  assert.match((await run('#' + code, randomUUID())).get('status').textContent, /邀请已更新/);
  assert.equal((await run('#%bad')).get('copy').disabled, true);
});
