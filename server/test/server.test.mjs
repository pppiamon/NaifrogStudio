import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { createService } from '../src/server.mjs';

test('job HTTP lifecycle, idempotent submission, result download and persisted restart', async t => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'naifrog-test-'));
  t.after(() => rm(dataDir, { recursive: true, force: true }));
  let calls = 0;
  const editor = async () => { calls++; await delay(35); return Buffer.from('result-fixture'); };
  const server = await createService({ dataDir, editor, appToken: 'code' });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { Authorization: 'Bearer code', 'Content-Type': 'application/json' };
  const id = randomUUID(), body = JSON.stringify({ requestId: id, original: 'a', reference: 'b', selection: 'c' });
  assert.equal((await (await fetch(base + '/health')).json()).ready, true);
  assert.equal((await fetch(base + '/v1/status')).status, 401);
  assert.equal((await (await fetch(base + '/v1/status', { headers })).json()).ready, true);
  assert.equal((await fetch(base + '/v1/jobs', { method: 'POST', body })).status, 401);
  assert.equal((await fetch(base + '/v1/jobs', { method: 'POST', headers, body })).status, 202);
  assert.equal((await fetch(base + '/v1/jobs', { method: 'POST', headers, body })).status, 200);
  let job;
  for (let i = 0; i < 30; i++) { job = await (await fetch(base + '/v1/jobs/' + id, { headers })).json(); if (job.status === 'completed') break; await delay(20); }
  assert.equal(job.status, 'completed'); assert.equal(calls, 1);
  assert.equal(await (await fetch(base + '/v1/jobs/' + id + '/result', { headers })).text(), 'result-fixture');
  await new Promise(resolve => server.close(resolve));
  const restored = await createService({ dataDir, editor, appToken: 'code' });
  await new Promise(resolve => restored.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => restored.close(resolve)));
  assert.equal((await (await fetch(`http://127.0.0.1:${restored.address().port}/v1/jobs/${id}`, { headers })).json()).status, 'completed');
});

test('queued and running jobs can be cancelled and malformed requests are rejected', async t => {
  const dataDir = await mkdtemp(path.join(os.tmpdir(), 'naifrog-cancel-'));
  const server = await createService({ dataDir, concurrency: 1, editor: async (payload, config, signal) => { await delay(200, null, { signal }); return Buffer.from('result'); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await delay(40); await rm(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(base + '/v1/jobs', { method: 'POST', body: '{' })).status, 400);
  const id = randomUUID();
  await fetch(base + '/v1/jobs', { method: 'POST', body: JSON.stringify({ requestId: id, original: 'a', reference: 'b', selection: 'c' }) });
  const cancel = await (await fetch(base + '/v1/jobs/' + id + '/cancel', { method: 'POST' })).json();
  assert.equal(cancel.status, 'cancelled');
  await delay(50);
  assert.equal((await fetch(base + '/v1/jobs/' + id + '/result')).status, 409);
});
