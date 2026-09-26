export const PAIR_PAGE = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>加入奶蛙照相馆</title>
<style>body{font:17px system-ui;max-width:580px;margin:32px auto;padding:24px;background:#f7f8f2;color:#26543c}h1{font-size:30px}p{line-height:1.7}.step{background:white;border-radius:20px;padding:20px;margin:16px 0}a,button{display:block;box-sizing:border-box;width:100%;padding:16px;margin:12px 0;background:#26543c;color:white;border:0;border-radius:14px;text-align:center;text-decoration:none;font:inherit}textarea{width:100%;height:86px;box-sizing:border-box;font-size:12px}#status{background:#e4edd6;padding:14px;border-radius:12px}.muted{color:#647165;font-size:14px}</style>
<h1>加入奶蛙照相馆</h1><p>收到邀请后，按下面两步连接。每个人登录自己的 Codex 账号。</p><p id="status" role="status">正在检查邀请入口…</p>
<section class="step"><b>1 · 安装或更新 App</b><a id="download" href="/download/naifrog.apk">下载 Android 安装包</a><p class="muted" id="package">安装后回到此页面，继续第二步。</p></section>
<section class="step"><b>2 · 连接并登录</b><a id="open">打开奶蛙并连接</a><button id="copy">复制连接码</button><p class="muted">如果浏览器没有打开 App：复制连接码，进入 App「账号 → 连接服务」，粘贴后点击连接。微信中可选择“在浏览器打开”后下载安装。</p><textarea id="code" readonly aria-label="连接码"></textarea></section>
<p class="muted">邀请者的电脑需保持开机和服务运行。入口更新后，使用邀请者发来的新链接即可继续连接。</p>
<script>
(async()=>{
 const status=document.getElementById('status'), open=document.getElementById('open'), copy=document.getElementById('copy');
 let code,profile;
 try{code=decodeURIComponent(location.hash.slice(1));if(!code.startsWith('NAIFROG1.')||code.length>16000)throw Error();
 let raw=code.slice(9).replace(/-/g,'+').replace(/_/g,'/');raw+='='.repeat((4-raw.length%4)%4);
 profile=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(raw),c=>c.charCodeAt(0))));
 if(profile.version!==1||!Array.isArray(profile.endpoints)||!profile.serviceId)throw Error();
 }catch{status.textContent='请使用邀请者发来的完整链接，或扫描最新连接二维码。';open.textContent='等待有效邀请';copy.disabled=true;return;}
 open.href='naifrog://connect?code='+encodeURIComponent(code);document.getElementById('code').value=code;
 copy.onclick=async()=>{try{await navigator.clipboard.writeText(code);copy.textContent='已复制，到 App 中粘贴';}catch{document.getElementById('code').select();copy.textContent='请长按连接码并复制';}};
 try{const response=await fetch('/health',{cache:'no-store',signal:AbortSignal.timeout(10000)});if(!response.ok)throw Error();const health=await response.json();
 status.textContent=health.serviceId!==profile.serviceId?'邀请已更新，请向邀请者获取最新链接。':health.ready?'服务在线，可以安装并连接':'服务在线，生成方式正在配置';
 }catch{status.textContent='暂未连通，请刷新页面，或向邀请者获取最新链接。';}
 try{const response=await fetch('/download/naifrog.apk',{method:'HEAD',cache:'no-store',signal:AbortSignal.timeout(10000)});if(!response.ok)document.getElementById('package').textContent='安装包正在准备，可先向邀请者获取 APK。';}catch{}
})();
</script></html>`;
