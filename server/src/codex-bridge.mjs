import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, access, realpath } from 'node:fs/promises';
import path from 'node:path';
import { prepareImages, mergeResult, PROMPT } from './image-pipeline.mjs';

export function bundledCodexCommand() {
  const cpu = { x64: 'x86_64', arm64: 'aarch64' }[process.arch];
  const target = { win32: 'pc-windows-msvc', linux: 'unknown-linux-musl', darwin: 'apple-darwin' }[process.platform];
  if (!cpu || !target) throw new Error('请通过 CODEX_COMMAND 指定当前系统的 Codex 可执行文件');
  const require = createRequire(import.meta.url);
  const pkg = require.resolve(`@openai/codex-${process.platform}-${process.arch}/package.json`);
  return path.join(path.dirname(pkg), 'vendor', `${cpu}-${target}`, 'bin', process.platform === 'win32' ? 'codex.exe' : 'codex');
}

export function sessionId(token) {
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) return '';
  return createHash('sha256').update(token).digest('hex');
}

export class CodexRpc extends EventEmitter {
  constructor(command, home, cwd) {
    super();
    this.pending = new Map(); this.nextId = 0; this.closed = false;
    const env = { ...process.env, CODEX_HOME: home };
    for (const key of ['OPENAI_API_KEY', 'OPENAI_BASE_URL', 'CODEX_API_KEY', 'CODEX_THREAD_ID', 'CODEX_INTERNAL_ORIGINATOR_OVERRIDE']) delete env[key];
    this.child = spawn(command || bundledCodexCommand(), ['app-server', '--listen', 'stdio://',
      '-c', 'cli_auth_credentials_store="file"', '-c', 'features.image_generation=true',
      '-c', 'features.shell_tool=false', '-c', 'features.apps=false', '-c', 'web_search="disabled"'],
    { cwd, env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child.stderr.on('data', () => {});
    this.child.stdin.on('error', error => this.fail(error));
    this.child.on('error', error => this.fail(new Error(`Codex 无法启动：${error.message}`)));
    this.child.on('exit', () => this.fail(new Error('Codex 进程已结束，请重试')));
    createInterface({ input: this.child.stdout }).on('line', line => {
      let message; try { message = JSON.parse(line); } catch { return; }
      if (message.id !== undefined && !message.method) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id); clearTimeout(pending.timer);
        if (message.error) pending.reject(new Error(message.error.message)); else pending.resolve(message.result);
      } else if (message.method && message.id !== undefined) {
        this.child.stdin.write(JSON.stringify({ id: message.id, error: { code: -32601, message: '此客户端仅处理图像生成' } }) + '\n');
      } else if (message.method) this.emit('notification', message);
    });
  }
  fail(error) {
    if (this.closed) return; this.closed = true;
    for (const request of this.pending.values()) { clearTimeout(request.timer); request.reject(error); }
    this.pending.clear(); this.emit('closed', error);
  }
  call(method, params = {}, timeout = 90000) {
    if (this.closed) return Promise.reject(new Error('Codex 连接已结束'));
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${method} 响应超时`)); }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
    });
  }
  async initialize() {
    await this.call('initialize', { clientInfo: { name: 'naifrog_studio', title: '奶蛙照相馆', version: '1.2.0' } });
    this.child.stdin.write(JSON.stringify({ method: 'initialized', params: {} }) + '\n');
    return this;
  }
  close() { this.child.kill(); this.fail(new Error('Codex 连接已关闭')); }
}

// Subscribe before turn/start: fast turns can emit completion before its response.
export async function runImageTurn(rpc, threadId, input, signal) {
  let turnId, finished = false, latestImage, message = '';
  let resolveDone, rejectDone;
  const done = new Promise((resolve, reject) => { resolveDone = resolve; rejectDone = reject; });
  done.catch(() => {});
  const onClosed = error => rejectDone(error);
  const onAbort = () => {
    if (turnId) void rpc.call('turn/interrupt', { threadId, turnId }).catch(() => {});
    rejectDone(new Error('生成任务已取消'));
  };
  const onNotification = ({ method, params }) => {
    if (params?.threadId !== threadId) return;
    if (method === 'item/completed') {
      if (params.item.type === 'imageGeneration' && params.item.status === 'completed') latestImage = params.item;
      if (params.item.type === 'agentMessage') message = params.item.text || '';
    }
    if (method === 'turn/completed') {
      finished = true;
      for (const item of params.turn.items || []) if (item.type === 'imageGeneration' && item.status === 'completed') latestImage = item;
      if (params.turn.status !== 'completed') rejectDone(new Error(params.turn.error?.message || 'Codex 未完成图像生成'));
      else if (!latestImage) rejectDone(new Error(message || 'Codex 本轮没有返回图像，请重新生成'));
      else resolveDone(latestImage);
    }
  };
  rpc.on('notification', onNotification); rpc.on('closed', onClosed);
  signal?.addEventListener('abort', onAbort, { once: true });
  try {
    if (signal?.aborted) throw new Error('生成任务已取消');
    const result = await rpc.call('turn/start', { threadId, input });
    turnId = result.turn.id;
    if (signal?.aborted && !finished) onAbort();
    return await done;
  } finally {
    rpc.off('notification', onNotification); rpc.off('closed', onClosed);
    signal?.removeEventListener('abort', onAbort);
  }
}

export class CodexManager {
  constructor(config = {}) {
    this.root = path.resolve(config.dataDir || './data', 'codex-sessions');
    this.command = config.codexCommand || ''; this.model = config.codexModel || '';
    this.factory = config.rpcFactory || ((home, cwd) => new CodexRpc(this.command, home, cwd));
    this.entries = new Map(); this.opening = new Map();
  }
  async get(token) {
    const id = sessionId(token);
    if (!id) throw Object.assign(new Error('请先登录 Codex'), { status: 401 });
    if (this.entries.has(id)) {
      const entry = this.entries.get(id);
      if (!entry.rpc.closed) { this.touch(entry); return entry; }
      this.entries.delete(id);
    }
    if (this.opening.has(id)) return this.opening.get(id);
    const opening = (async () => {
      const folder = path.join(this.root, id);
      try { await access(path.join(folder, 'session.json')); }
      catch { throw Object.assign(new Error('登录记录已失效，请重新登录'), { status: 401 }); }
      if (this.entries.size >= 32) throw Object.assign(new Error('当前登录人数较多，请稍后再试'), { status: 429 });
      const home = path.join(folder, 'home'), cwd = path.join(folder, 'images');
      await mkdir(home, { recursive: true }); await mkdir(cwd, { recursive: true });
      const rpc = this.factory(home, cwd);
      const entry = { id, folder, home, cwd, rpc, login: null, busy: false };
      rpc.on('notification', ({ method, params }) => {
        if (method === 'account/login/completed' && entry.login?.loginId === params.loginId) {
          entry.login = { ...entry.login, state: params.success ? 'completed' : 'failed', error: params.error || '' };
        }
      });
      try { await rpc.initialize(); } catch (error) { rpc.close(); throw error; }
      this.entries.set(id, entry); this.touch(entry); return entry;
    })();
    this.opening.set(id, opening);
    try { return await opening; } finally { this.opening.delete(id); }
  }
  touch(entry) {
    clearTimeout(entry.idle);
    entry.idle = setTimeout(() => {
      if (entry.busy) { this.touch(entry); return; }
      entry.rpc.close(); this.entries.delete(entry.id);
    }, 15 * 60 * 1000);
    entry.idle.unref();
  }
  async startLogin(token) {
    if (!token) {
      token = randomBytes(32).toString('base64url');
      const folder = path.join(this.root, sessionId(token));
      await mkdir(folder, { recursive: true });
      await writeFile(path.join(folder, 'session.json'), JSON.stringify({ createdAt: Date.now() }));
    }
    const entry = await this.get(token);
    if (entry.busy) throw new Error('生成完成后可切换账号');
    if (entry.login?.state === 'pending') return { sessionToken: token, ...entry.login };
    if (entry.startingLogin) return entry.startingLogin;
    entry.startingLogin = (async () => {
      const login = await entry.rpc.call('account/login/start', { type: 'chatgptDeviceCode' });
      if (login.type !== 'chatgptDeviceCode' || !login.verificationUrl || !login.userCode) throw new Error('请升级服务端 Codex，当前版本没有返回设备验证码');
      entry.login = { ...login, state: 'pending' };
      return { sessionToken: token, ...entry.login };
    })();
    try { return await entry.startingLogin; } finally { entry.startingLogin = null; }
  }
  async account(token) {
    const entry = await this.get(token);
    const { account } = await entry.rpc.call('account/read', { refreshToken: false });
    const authenticated = account?.type === 'chatgpt';
    return { authenticated, account: authenticated ? { email: account.email, planType: account.planType } : null,
      login: authenticated ? { state: 'completed' } : entry.login || { state: 'idle' } };
  }
  async logout(token) {
    const entry = await this.get(token);
    if (entry.busy) throw new Error('请先完成或取消当前生成任务');
    if (entry.login?.state === 'pending') await entry.rpc.call('account/login/cancel', { loginId: entry.login.loginId });
    await entry.rpc.call('account/logout'); entry.login = null;
    return { authenticated: false, account: null, login: { state: 'idle' } };
  }
  async edit(payload, token, signal) {
    const entry = await this.get(token);
    if (entry.busy) throw new Error('该 Codex 账号已有生成任务，请等待完成');
    entry.busy = true;
    let threadId;
    try {
      if (!(await this.account(token)).authenticated) throw new Error('请在手机上完成 Codex 登录后重新生成');
      const p = await prepareImages(payload);
      const cwd = path.join(entry.cwd, payload.requestId); await mkdir(cwd, { recursive: true });
      const files = ['01-original.png', '02-naifrog-reference.png', '03-selection-mask.png'].map(name => path.join(cwd, name));
      await Promise.all([p.padded, p.reference, p.mask].map((buffer, i) => writeFile(files[i], buffer)));
      const params = { cwd, ephemeral: true, approvalPolicy: 'never', sandbox: 'read-only',
        developerInstructions: 'You edit photographs using the built-in image_generation tool. Always generate the requested image with that tool. Do not use shell commands, coding tools, or external image APIs. Preserve the supplied character reference exactly. Return one generated image.',
        config: { 'features.image_generation': true, 'features.shell_tool': false, 'web_search': 'disabled' } };
      if (this.model) params.model = this.model;
      const started = await entry.rpc.call('thread/start', params); threadId = started.thread.id;
      const text = PROMPT + `\n请调用内置 image_generation 工具进行图片编辑，生成一张 ${p.canvasWidth}×${p.canvasHeight} 的图片。第一张图是原始照片，第二张图是奶蛙外观参考。第三张是选区遮罩：透明区域允许编辑，白色区域保持不变。目标可以是真人、卡通或拟人化角色。仅将选区内目标角色的面部/身体变成参考图的奶蛙；保留头发、衣服及其余人物或角色。第一张图的灰色留白边框也必须保留，不要缩放、平移或裁切原始内容。`;
      const item = await runImageTurn(entry.rpc, threadId, [{ type: 'text', text }, ...files.map(file => ({ type: 'localImage', path: file }))], signal);
      let image;
      const base64 = item.result?.replace(/^data:image\/[a-z0-9.+-]+;base64,/i, '');
      if (base64 && /^[A-Za-z0-9+/\r\n]+={0,2}$/.test(base64)) image = Buffer.from(base64, 'base64');
      else if (item.savedPath) {
        const saved = await realpath(item.savedPath), root = await realpath(entry.folder);
        const relative = path.relative(root, saved);
        if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Codex 图片输出路径不在当前创作目录中');
        image = await readFile(saved);
      } else throw new Error('Codex 没有返回可读取的图片');
      return await mergeResult(p, image);
    } finally {
      if (threadId) await entry.rpc.call('thread/unsubscribe', { threadId }).catch(() => {});
      entry.busy = false; this.touch(entry);
    }
  }
  close() { for (const entry of this.entries.values()) { clearTimeout(entry.idle); entry.rpc.close(); } this.entries.clear(); }
}
