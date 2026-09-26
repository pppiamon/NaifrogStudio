import http from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir, rm, rename, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { networkInterfaces } from 'node:os';
import { editImage } from './image-pipeline.mjs';
import { CodexManager, sessionId } from './codex-bridge.mjs';
import { PAIR_PAGE } from './join-page.mjs';

export async function createService(options = {}) {
  const config = {
    apiKey: process.env.IMAGE_API_KEY || '', baseUrl: process.env.IMAGE_BASE_URL || 'https://api.openai.com/v1',
    model: process.env.IMAGE_MODEL || 'gpt-image-1.5', quality: process.env.IMAGE_QUALITY || 'high',
    appToken: process.env.APP_TOKEN || '', dataDir: path.resolve(process.env.DATA_DIR || './data'), apkPath: process.env.PUBLIC_APK_PATH || '',
    concurrency: Number(process.env.MAX_CONCURRENT_JOBS || 2),
    codexEnabled: process.env.CODEX_ENABLED === 'true', codexCommand: process.env.CODEX_COMMAND || '',
    codexModel: process.env.CODEX_MODEL || '', ...options
  };
  const codex = config.codexEnabled ? config.codexManager || new CodexManager(config) : null;
  const jobs = new Map(), queue = [], controllers = new Map();
  let active = 0;
  await mkdir(config.dataDir, { recursive: true });
  const identityFile = path.join(config.dataDir, 'service-id');
  let serviceId;
  try { serviceId = (await readFile(identityFile, 'utf8')).trim(); }
  catch { serviceId = randomUUID(); await writeFile(identityFile, serviceId); }
  const loginRequests = new Map();
  const file = (id, suffix) => path.join(config.dataDir, `${id}.${suffix}`);
  const save = async job => {
    const destination = file(job.id, 'json');
    const temporary = `${destination}.${randomUUID()}.tmp`;
    await writeFile(temporary, JSON.stringify(job));
    await rename(temporary, destination);
  };
  for (const name of await readdir(config.dataDir)) {
    if (!name.endsWith('.json')) continue;
    try {
      const job = JSON.parse(await readFile(path.join(config.dataDir, name), 'utf8'));
      if (!/^[a-f0-9-]{36}$/.test(job.id)) continue;
      if (Date.now() - job.createdAt > 86400000) {
        await Promise.all(['json', 'png'].map(ext => rm(file(job.id, ext), { force: true })));
        continue;
      }
      if (['queued', 'running'].includes(job.status)) {
        job.status = 'failed'; job.error = '服务已重启，请重新生成'; await save(job);
      }
      jobs.set(job.id, job);
    } catch { /* Ignore incomplete job metadata left by an interrupted write. */ }
  }
  async function pump() {
    while (active < Math.max(1, config.concurrency) && queue.length) {
      const { job, payload, session } = queue.shift();
      if (job.status === 'cancelled') continue;
      active++;
      job.status = 'running';
      const controller = new AbortController();
      controllers.set(job.id, controller);
      const timeout = setTimeout(() => controller.abort(), 600000);
      void (async () => {
        try {
          await save(job);
          const image = job.provider === 'codex' ? await codex.edit(payload, session, controller.signal)
            : await (config.editor || editImage)(payload, config, controller.signal);
          if (job.status !== 'cancelled') {
            await writeFile(file(job.id, 'png'), image);
            job.status = 'completed';
          }
        } catch (e) {
          if (job.status !== 'cancelled') { job.status = 'failed'; job.error = controller.signal.aborted ? '生成超时，请重试' : e.message; }
        } finally {
          clearTimeout(timeout); controllers.delete(job.id);
          await save(job).catch(console.error);
          active--; void pump();
        }
      })();
    }
  }
  const respond = (res, status, value) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(value)); };
  const publicJob = ({ owner, ...job }) => job;
  const status = () => ({ ready: Boolean(config.apiKey || config.editor || codex), version: '1.4.0', serviceId,
    providers: { api: Boolean(config.apiKey || config.editor), codex: Boolean(codex) } });
  const server = http.createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url, 'http://local').pathname;
      if (pathname === '/pair' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(PAIR_PAGE); return;
      }
      if (pathname === '/health' && req.method === 'GET') {
        return respond(res, 200, { status: 'ok', ...status() });
      }
      if (pathname === '/download/naifrog.apk' && ['GET', 'HEAD'].includes(req.method)) {
        let info;
        try { if (config.apkPath) info = await stat(config.apkPath); } catch { }
        if (!info?.isFile()) return respond(res, 404, { error: '安装包正在准备，请稍后刷新，或向邀请者获取 APK' });
        res.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': info.size,
          'Content-Disposition': 'attachment; filename="naifrog-latest.apk"', 'Cache-Control': 'no-store' });
        if (req.method === 'HEAD') { res.end(); return; }
        const stream = createReadStream(config.apkPath);
        stream.on('error', () => res.destroy()); res.on('close', () => stream.destroy()); stream.pipe(res); return;
      }
      if (config.appToken) {
        const received = Buffer.from(req.headers.authorization || '');
        const expected = Buffer.from(`Bearer ${config.appToken}`);
        if (received.length !== expected.length || !timingSafeEqual(received, expected)) return respond(res, 401, { error: '连接码不正确' });
      }
      const session = req.headers['x-codex-session'] || '';
      if (pathname === '/v1/status' && req.method === 'GET') return respond(res, 200, status());
      if (pathname.startsWith('/v1/codex/')) {
        if (!codex) return respond(res, 503, { error: '服务端尚未启用 Codex 登录，请设置 CODEX_ENABLED=true' });
        if (pathname === '/v1/codex/login' && req.method === 'POST') {
          const requestId = req.headers['x-request-id'];
          if (!requestId) return respond(res, 200, await codex.startLogin(session));
          if (!/^[a-f0-9-]{36}$/i.test(requestId)) return respond(res, 400, { error: '登录请求编号无效' });
          const key = sessionId(session) + ':' + requestId;
          for (const [id, entry] of loginRequests) if (entry.expires < Date.now()) loginRequests.delete(id);
          if (!loginRequests.has(key)) {
            const result = codex.startLogin(session);
            loginRequests.set(key, { result, expires: Date.now() + 10 * 60 * 1000 });
            result.catch(() => loginRequests.delete(key));
          }
          return respond(res, 200, await loginRequests.get(key).result);
        }
        if (pathname === '/v1/codex/account' && req.method === 'GET') return respond(res, 200, await codex.account(session));
        if (pathname === '/v1/codex/logout' && req.method === 'POST') {
          if ([...jobs.values()].some(job => job.owner === sessionId(session) && ['queued', 'running'].includes(job.status))) return respond(res, 409, { error: '请先完成或取消当前生成任务' });
          return respond(res, 200, await codex.logout(session));
        }
      }
      if (req.method === 'POST' && pathname === '/v1/jobs') {
        let bytes = 0; const chunks = [];
        for await (const chunk of req) {
          bytes += chunk.length;
          if (bytes > 48000000) { respond(res, 413, { error: '图片过大，请缩小后重试' }); return; }
          chunks.push(chunk);
        }
        let payload;
        try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
        catch { return respond(res, 400, { error: '请求格式无效' }); }
        if (!['original', 'reference', 'selection'].every(key => typeof payload[key] === 'string' && payload[key].length > 0)) return respond(res, 400, { error: '需要原图、奶蛙参考图和选区' });
        const id = payload.requestId;
        if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(id || '')) return respond(res, 400, { error: 'requestId 必须为 UUID' });
        const provider = payload.provider || 'api';
        if (!['api', 'codex'].includes(provider)) return respond(res, 400, { error: '请选择 Codex 或 API 生成方式' });
        const owner = provider === 'codex' ? sessionId(session) : '';
        if (provider === 'codex' && !owner) return respond(res, 401, { error: '请先登录 Codex' });
        if (jobs.has(id)) {
          const job = jobs.get(id);
          if ((job.owner || '') !== owner) return respond(res, 404, { error: '未找到生成任务' });
          return respond(res, 200, publicJob(job));
        }
        if (provider === 'api' && !config.apiKey && !config.editor) return respond(res, 503, { error: '服务尚未配置图像 API Key' });
        if (provider === 'codex') {
          if (!codex) return respond(res, 503, { error: '服务尚未启用 Codex' });
          if (!(await codex.account(session)).authenticated) return respond(res, 401, { error: '请先完成 Codex 登录' });
          if (jobs.has(id)) {
            const existing = jobs.get(id);
            return existing.owner === owner ? respond(res, 200, publicJob(existing)) : respond(res, 404, { error: '未找到生成任务' });
          }
          if ([...jobs.values()].some(job => job.owner === owner && ['queued', 'running'].includes(job.status))) return respond(res, 409, { error: '当前账号已有生成任务，请等待完成' });
        }
        if (queue.length >= 20) return respond(res, 429, { error: '当前任务较多，请稍后再试' });
        const job = { id, provider, owner, status: 'queued', createdAt: Date.now() };
        jobs.set(id, job); await save(job); queue.push({ job, payload, session });
        respond(res, 202, publicJob(job)); void pump(); return;
      }
      const match = pathname.match(/^\/v1\/jobs\/([a-f0-9-]{36})(\/result|\/cancel)?$/i);
      if (match) {
        const job = jobs.get(match[1]);
        if (!job || (job.owner && job.owner !== sessionId(session))) return respond(res, 404, { error: '未找到生成任务，请重新生成' });
        if (req.method === 'GET' && !match[2]) return respond(res, 200, publicJob(job));
        if (req.method === 'POST' && match[2] === '/cancel') {
          if (['queued', 'running'].includes(job.status)) { job.status = 'cancelled'; controllers.get(job.id)?.abort(); await save(job); }
          return respond(res, 200, publicJob(job));
        }
        if (req.method === 'GET' && match[2] === '/result') {
          if (job.status !== 'completed') return respond(res, 409, { error: '图片尚未生成完成' });
          const png = await readFile(file(job.id, 'png'));
          res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length }); res.end(png); return;
        }
      }
      respond(res, 404, { error: '接口不存在' });
    } catch (e) { if (!res.headersSent) respond(res, e.status || 500, { error: e.message || '生成服务暂时不可用' }); else res.end(); }
  });
  server.requestTimeout = 120000;
  server.on('close', () => { for (const controller of controllers.values()) controller.abort(); codex?.close(); });
  return server;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = await createService();
  const port = Number(process.env.PORT || 8787);
  server.listen(port, process.env.HOST || '0.0.0.0', () => {
    console.log(`奶蛙生成服务已启动，端口 ${port}`);
    for (const [name, addresses] of Object.entries(networkInterfaces())) {
      for (const info of addresses || []) if (info.family === 'IPv4' && !info.internal && !info.address.startsWith('169.254.')) {
        console.log(`手机生成服务地址（${name}）：http://${info.address}:${port}`);
      }
    }
    console.log(process.env.APP_TOKEN ? '连接码：使用 .env 中设置的 APP_TOKEN' : '连接码：留空');
    console.log('手机与电脑连接同一 Wi-Fi，在 App 设置中填写上述地址。');
  });
}
