import { createService } from './server.mjs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import QRCode from 'qrcode';

export function pairingCode(profile) { return 'NAIFROG1.' + Buffer.from(JSON.stringify(profile)).toString('base64url'); }

export async function startConnector({ cloudflared, root, output, port = 18787, apkPath = process.env.PUBLIC_APK_PATH || '' }) {
  await mkdir(root, { recursive: true }); await mkdir(output, { recursive: true });
  const tokenFile = path.join(root, 'connection-token');
  let token;
  try { token = (await readFile(tokenFile, 'utf8')).trim(); }
  catch { token = randomBytes(32).toString('base64url'); await writeFile(tokenFile, token); }
  const server = await createService({ dataDir: root, appToken: token, codexEnabled: true, apkPath });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  const localUrl = `http://127.0.0.1:${server.address().port}`;
  const identity = await (await fetch(localUrl + '/health')).json();
  console.log('电脑服务已就绪，正在建立 HTTPS 连接…');
  let child, closed = false, retry, lastUrl = '', attempts = 0;
  const saveProfile = async url => {
    lastUrl = url;
    const profile = { version: 1, serviceId: identity.serviceId, endpoints: [url], token };
    const code = pairingCode(profile);
    const invitation = url + '/pair#' + code;
    await writeFile(path.join(output, 'connection.json'), JSON.stringify(profile, null, 2));
    await writeFile(path.join(output, '手机连接码.txt'), code);
    await writeFile(path.join(output, '分享给朋友.txt'), invitation);
    const deepLink = 'naifrog://connect?code=' + encodeURIComponent(code);
    const qr = await QRCode.toString(invitation, { type: 'svg', width: 340, margin: 2 });
    await writeFile(path.join(output, '手机连接二维码.svg'), qr);
    await writeFile(path.join(output, '手机连接.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>连接奶蛙照相馆</title><style>body{font:18px system-ui;max-width:680px;margin:60px auto;padding:24px;background:#f7f8f2;color:#26543c}textarea{width:100%;height:140px}a,button{display:inline-block;padding:16px;margin:16px 0;background:#26543c;color:white;border:0;border-radius:12px;text-decoration:none}</style><h1>连接奶蛙照相馆</h1><p>在手机 App 设置中导入下面的连接码，或在手机上点击“打开奶蛙并连接”。电脑服务窗口保持开启。</p><p>服务地址：${url}</p><a href="${deepLink}">打开奶蛙并连接</a><textarea readonly id="code">${code}</textarea><button onclick="navigator.clipboard.writeText(document.getElementById('code').value).then(()=>this.textContent='已复制')">复制连接码</button><p>重新启动后，使用这里更新的连接码即可继续连接同一台电脑。</p></html>`);
    const html = await readFile(path.join(output, '手机连接.html'), 'utf8');
    await writeFile(path.join(output, '手机连接.html'), html.replace('<h1>连接奶蛙照相馆</h1>', '<h1>连接奶蛙照相馆</h1><p>将“分享给朋友.txt”中的完整链接发给朋友，对方可下载安装并连接。</p><a href="' + invitation + '">打开安装与连接页</a><p>手机扫码：</p>' + qr));
    console.log(`HTTPS 入口：${url}`);
    console.log(`手机连接文件已更新：${output}`);
  };
  const launch = () => {
    if (closed) return;
    let buffer = '';
    child = spawn(cloudflared, ['tunnel', '--no-autoupdate', '--protocol', 'http2', '--edge-ip-version', '4', '--url', localUrl], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const line = chunk => {
      buffer += chunk.toString();
      let split;
      while ((split = buffer.indexOf('\n')) >= 0) {
        const row = buffer.slice(0, split); buffer = buffer.slice(split + 1);
        const url = row.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com\b/);
        if (url && url[0] !== lastUrl) void saveProfile(url[0]).catch(console.error);
        if (row.includes('Registered tunnel connection')) { attempts = 0; console.log('HTTPS 通道已连接'); }
        if (row.includes('ERR')) console.error(row.replace(/token=[^\s]+/gi, 'token=[omitted]'));
      }
    };
    child.stdout.on('data', line); child.stderr.on('data', line);
    child.on('error', error => console.error('通道启动失败：' + error.message));
    child.on('close', () => {
      if (!closed) { const wait = Math.min(30000, 2000 * 2 ** Math.min(attempts++, 4)); console.log(`连接中断，${wait / 1000} 秒后重连…`); retry = setTimeout(launch, wait); }
    });
  };
  launch();
  return { server, close: async () => { closed = true; clearTimeout(retry); child?.kill(); await new Promise(resolve => server.close(resolve)); } };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let existing;
  try { existing = await (await fetch('http://127.0.0.1:18787/health', { signal: AbortSignal.timeout(1500) })).json(); } catch { }
  if (existing?.serviceId && existing?.providers?.codex) {
    if (existing.version !== '1.4.0') throw new Error('旧版服务仍在运行，请关闭旧服务窗口后重新启动。');
    console.log('自动连接服务已在运行。连接文件：' + path.resolve(process.env.CONNECTION_OUTPUT || './connection'));
    process.exit(0);
  }
  const connector = await startConnector({ cloudflared: process.env.CLOUDFLARED_PATH || 'cloudflared', root: path.resolve(process.env.CONNECTOR_DATA || './data-connected'), output: path.resolve(process.env.CONNECTION_OUTPUT || './connection') });
  const stop = () => { void connector.close().then(() => process.exit()); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
}
