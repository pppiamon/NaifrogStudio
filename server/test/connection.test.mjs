import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createService } from '../src/server.mjs';
import { pairingCode } from '../src/connect.mjs';

test('service identity survives restart and login retries create only one device code', async t => {
  const root = await mkdtemp(path.join(tmpdir(), 'naifrog-connection-'));
  let calls = 0;
  const codex = { close() {}, async startLogin() { calls++; await new Promise(resolve => setTimeout(resolve, 30)); return { sessionToken: 'fixture-session', userCode: 'TEST-1234' }; } };
  let server = await createService({ dataDir: root, codexEnabled: true, codexManager: codex, appToken: 'fixture' });
  t.after(async () => { if (server.listening) await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true, force: true }); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let url = `http://127.0.0.1:${server.address().port}`;
  const first = await (await fetch(url + '/health')).json();
  assert.match(first.serviceId, /^[a-f0-9-]{36}$/);
  const headers = { Authorization: 'Bearer fixture', 'X-Request-Id': randomUUID() };
  const replies = await Promise.all(Array.from({ length: 4 }, async () => (await fetch(url + '/v1/codex/login', { method: 'POST', headers })).json()));
  assert.equal(calls, 1); assert.ok(replies.every(reply => reply.sessionToken === 'fixture-session'));
  assert.equal((await fetch(url + '/v1/codex/login', { method: 'POST' })).status, 401);
  await new Promise(resolve => server.close(resolve));
  server = await createService({ dataDir: root, codexEnabled: true, codexManager: codex });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  url = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await (await fetch(url + '/health')).json()).serviceId, first.serviceId);
});

test('pairing code round-trips all connection endpoints and server identity', () => {
  const profile = { version: 1, serviceId: randomUUID(), endpoints: ['https://one.example', 'https://two.example'], token: 'fixture' };
  const code = pairingCode(profile);
  assert.ok(code.startsWith('NAIFROG1.'));
  assert.deepEqual(JSON.parse(Buffer.from(code.slice(9), 'base64url').toString()), profile);
});
