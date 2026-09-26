# 奶蛙照相馆账号与生成服务 · 1.2.0

供奶蛙照相馆 APK 连接，支持手机 Codex 设备码登录和可配置图像 API。

## Node.js 部署

安装 Node.js 22 或以上，进入本目录：

```powershell
npm ci
Copy-Item .env.example .env
npm start
```

Windows 也可运行 `start.ps1`，首次运行自动创建 `.env` 并安装依赖。Linux 复制配置使用 `cp .env.example .env`。

默认端口 `8787`，默认启用 Codex。`npm ci` 会安装 Codex 0.155.1 的本机版本，管理员无需先登录。`IMAGE_API_KEY` 留空即可使用 Codex 账号方式。

启动窗口会显示「手机生成服务地址」，直接填入 App「设置」。连接码填写 `.env` 中的 `APP_TOKEN`，默认留空。同一局域网地址类似 `http://192.168.1.8:8787`，线上部署填写服务域名。`GET /health` 可查看启动状态。

## Docker 部署

在本目录执行：

```sh
cp .env.example .env
docker compose up -d --build
docker compose logs -f
```

Compose 默认映射 `8787` 端口，将账号会话和生成结果保存在命名卷 `naifrog-data`。修改 `.env` 后执行 `docker compose up -d` 应用配置。Codex 已包含在容器依赖中。

## 手机上登录

1. 打开「账号 → Codex 账号 → 登录 Codex」。
2. 获取验证码，点击「复制验证码并打开登录页」。
3. 在官方页面登录自己的 ChatGPT / Codex 账号，输入验证码。
4. 返回奶蛙照相馆；显示已登录后即可生成。

登录使用官方 App Server 的 `chatgptDeviceCode` 流程，图片通过 Codex 内置 `image_generation` 工具编辑。原图、奶蛙参考图、选区遮罩按顺序传入，生成后按选区合成回原图。1.2.0 补充了真人、卡通和拟人角色的选区编辑指令，保留用户完整原始提示词。

## 配置

| 配置项 | 用途 |
| --- | --- |
| `PORT` / `HOST` | 默认 `8787` / `0.0.0.0` |
| `APP_TOKEN` | App 连接码，设置后在手机端填写相同值 |
| `CODEX_ENABLED` | `true` 启用 Codex 手机登录 |
| `CODEX_COMMAND` | 留空使用安装的 Codex；可指定可执行文件完整路径 |
| `CODEX_MODEL` | 留空使用默认模型 |
| `IMAGE_API_KEY` / `IMAGE_BASE_URL` / `IMAGE_MODEL` | API 方式的密钥、地址和模型 |
| `MAX_CONCURRENT_JOBS` | 同时生成任务数，默认 2 |
| `DATA_DIR` | Node 默认 `./data`；Compose 固定为持久卷中的 `/data` |

更新时保留数据目录。服务重启后已完成图片可以继续下载，未完成任务可重新提交。单个 Codex 账号会话同时处理一个生成任务。

`npm test` 运行 16 项服务测试，使用本地夹具，不调用真实图片模型。交付时已实测官方验证码发放；完整账号授权与实际奶蛙转换效果等待部署后联调。

官方资料：[OpenAI Docs：App Server](https://learn.chatgpt.com/docs/app-server)、[内置图像生成](https://developers.openai.com/zh-Hans/docs/image-generation)。
