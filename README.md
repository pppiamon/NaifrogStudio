# 奶蛙照相馆 · Android 1.4.0

原生 Android 应用和可部署的图像编辑服务。支持在手机上登录自己的 Codex 账号生成，也支持可配置图像 API。应用导入照片后在本机检测人脸，选择人物和奶蛙参考图，调整替换范围，生成奶蛙照片。

个人试用：在交付目录双击 `启动奶蛙自动连接.cmd`，手机通过 HTTPS 连接电脑，可使用校园 Wi-Fi、手机流量或 VPN。当前 APK 已内置连接资料；电脑连接通道重启后，打开 `手机连接/手机连接.html`，手机扫码更新入口。详细行为、测试与固定域名发布方式见 [CONNECTIONS.md](CONNECTIONS.md)。

## 当前交付

- Android 9 及以上；目标 Android 16 / API 36。
- 本机 ML Kit 人脸检测，模型内置；多人照片按从左到右选择人物。
- 自动面部选区、手动框选、涂抹、擦除、笔刷大小、撤销和清空。
- 卡通、拟人角色和漏检人像可直接手动选区，不要求检测到人脸。
- 奶蛙图片库可批量导入、选择和移除；支持内置参考图片。
- 生成后原图对比、PNG 相册保存、系统分享、本地作品历史。
- 生成任务支持幂等提交、续取结果和取消。
- 原图处理长边最高 2048 像素，保持长宽比；PNG 输出保持处理后的原图尺寸。
- 选区之外直接采用处理后的原图像素，选区内部按模型结果合成。
- 「账号」页面切换 Codex / API；Codex 官方设备码登录、自动更新登录状态、退出账号。
- 服务为每个手机登录会话单独运行 Codex，任务和作品绑定对应会话。

检测到真人面部时提供自动椭圆选区；没有检测到人脸或检测失败时，提供手动框选和笔刷。拟人化角色也可直接转换。拖动框选面部后，用擦除保留头发、衣服，再按需要涂抹手臂等区域。草稿重新检测后保留已有手动选区。

已从用户提供的 `naiwa_original_frames` 图片库内置 12 张参考图，保留原始 PNG 文件；映射见 `reference-manifest.json`。安装后可先使用导入、识别和选区；账号登录和生成连接下述服务。1.2.0 沿用旧版包名和发布签名，可覆盖更新。

## 1. 启动生成服务

面向所有手机用户发布时，使用统一公网域名并内置到 APK，完整步骤见 [通用发布方案](PUBLIC-RELEASE.md)。`server/compose.public.yaml` 负责公网部署，`publish-apk.ps1` 负责检查服务并生成内置地址的安装包。

安装 Node.js 22 或以上，在 `server` 目录执行：

```powershell
npm ci
Copy-Item .env.example .env
```

默认启用 Codex 手机登录；`npm ci` 会同时安装固定版本 `@openai/codex@0.155.1`，无需单独安装或预先登录 Codex。`.env` 的基本配置：

```dotenv
PORT=8787
HOST=0.0.0.0
APP_TOKEN=你设置的连接码
CODEX_ENABLED=true
CODEX_COMMAND=
CODEX_MODEL=
IMAGE_API_KEY=
IMAGE_BASE_URL=https://api.openai.com/v1
IMAGE_MODEL=gpt-image-1.5
IMAGE_QUALITY=high
MAX_CONCURRENT_JOBS=2
DATA_DIR=./data
```

**Codex 方式**不需要填写 `IMAGE_API_KEY`。`CODEX_COMMAND` 留空使用项目安装的 Codex；也可以指定某个 Codex 可执行文件的完整路径。`CODEX_MODEL` 留空使用 Codex 默认模型。切换到 **API 方式**时，再填写 `IMAGE_API_KEY`、上游地址和模型。

`IMAGE_BASE_URL` 是上游的 API 基地址，程序会追加 `/images/edits`。可配置的服务需要兼容 OpenAI Images Edit 的 multipart 协议：`image[]` 多图输入、PNG `mask`、`prompt`、`size`、`quality`、`output_format`，并返回 `data[0].b64_json`。如果服务使用其他协议，修改 `server/src/image-pipeline.mjs` 中的 `editImage` 适配器即可。

```powershell
npm start
```

手机和电脑在同一网络时，应用「设置」填写启动窗口显示的「手机生成服务地址」，例如 `http://192.168.1.8:8787`。连接码填写 `APP_TOKEN`，默认留空。点击「测试连接」后保存。对外发布时，把相同服务部署到你的服务器，填写对应的 HTTPS 域名。

应用中的「生成服务地址」指本项目服务地址；上游图片 API 基地址、模型及密钥在服务端配置。

### 手机上登录 Codex

1. 在应用「设置」保存已部署的服务地址与连接码。
2. 打开「账号 → Codex 账号 → 登录 Codex」。
3. 应用显示官方设备验证码。点击「复制验证码并打开登录页」，在官方页面使用自己的 ChatGPT / Codex 账号登录并填写验证码。
4. 回到应用。页面会自动更新为已登录，并显示账号与套餐。
5. 点击「开始创作」，导入人像、选择奶蛙参考图和替换区域，然后生成。

调用链为「手机 App → 本项目账号服务 → Codex App Server → 内置图像生成」。部署完成后，用户电脑无需运行。服务当前使用官方 `account/login/start` 的 `chatgptDeviceCode` 模式；登录完成后使用 `thread/start`、`turn/start` 和 `imageGeneration` 结果事件。

当前交付不含已上线的服务域名。管理员先部署服务，或在同一网络的电脑上启动服务进行试用；发布时可用下文 `-PserviceUrl` 将服务域名预置到 APK。

官方依据：[OpenAI Docs：App Server 登录](https://learn.chatgpt.com/docs/app-server#3b-log-in-with-chatgpt-device-code-flow)、[内置图像生成](https://developers.openai.com/zh-Hans/docs/image-generation)。

Docker：

```sh
docker build -t naifrog-studio ./server
docker run -d --name naifrog-studio --env-file ./server/.env -e DATA_DIR=/data -p 8787:8787 -v naifrog-data:/data --restart unless-stopped naifrog-studio
```

## 2. 导入奶蛙形象

应用「角色库 → 批量导入参考图」，支持手机上的 JPG、PNG、WEBP 和系统支持的 HEIC。每次生成选择一张外观清晰的参考图。

预装图片：把奶蛙图片放到 `app/src/main/assets/references/` 后重新打包。应用首次启动时会复制到角色库。未放入参考图时显示导入入口，不会按角色名称自行生成角色形象。

## 3. 生成流程

1. 导入照片或角色图片，应用自动检测真人面部。
2. 检测到面部可直接使用自动选区；拟人角色、卡通或漏检人像使用「框选」，拖动划出面部或身体区域。
3. 涂抹或擦除微调范围，绿色表示替换区域，再选择一张奶蛙参考图。
4. 点击生成。服务按顺序提供原图、奶蛙参考图和选区遮罩，并提交完整提示词。Codex 方式通过内置图像生成工具编辑，API 方式通过 Images Edit 接口编辑。
5. 服务把图像适配到模型支持的画布；生成后去掉留白，再将选区内结果合成回原图。
6. 完成后切换原图对比，保存或分享。

生成服务中的提示词逐字保留用户的核心要求，另外补充选区、多人和画布位置指令。原始提示词见 `server/src/image-pipeline.mjs` 的 `PROMPT`。

## 4. 安卓打包

使用 JDK 17、Android SDK 36、Build Tools 35.0.0；Gradle Wrapper 8.13 和 Android Gradle Plugin 8.13.2 已固定。

```powershell
$env:JAVA_HOME = '你的JDK17路径'
$env:ANDROID_HOME = '你的Android SDK路径'
.\gradlew.bat assembleDebug
```

发布签名在项目旁的 `release-signing` 文件夹。`signing.properties` 指向该签名；后续版本沿用这组签名，并递增 `app/build.gradle` 的 `versionCode`。

```powershell
.\gradlew.bat assembleRelease bundleRelease
```

输出：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

预置服务域名：

```powershell
.\gradlew.bat assembleRelease '-PserviceUrl=https://你的生成服务域名'
```

若希望更换发布包名，修改 `applicationId`；当前包名为 `com.naifrog.studio`，应用名为「奶蛙照相馆」。

## 5. 验证

```powershell
cd server
npm test
cd ..
.\gradlew.bat lintRelease
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

服务测试覆盖四种画面比例的尺寸和选区外像素保留、遮罩方向、图片顺序、提示词、上游异常、任务重复提交、取消和重启恢复；另包含 Codex 设备登录、会话隔离、完成事件提前到达、终止生成、纯文字响应处理以及 Codex 返回图片的合成。测试中的图片响应为测试夹具。

安卓仪器测试覆盖页面、离线单人/多人检测、非人像拒绝、画笔选区与撤销。测试使用的 NASA 宇航员照片只放在测试包，不进入发布 APK。来源：[scikit-image 数据说明](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)。

Robolectric 组件测试检查角色素材加载、主界面、图库、编辑画布、笔刷与撤销、不可变选区恢复、服务地址格式，以及 Codex 未登录、验证码、已登录、切换服务和过期状态。另验证零人脸结果下框选和生成的本地 HTTP 完整流程、框选反向拖动/撤销/取消、检测失败后的手动选区保留。组件渲染使用 Android 15 框架，人脸检测在组件测试中使用替身。

真实 Codex 0.155.1 已验证 App Server 初始化、图像能力查询、官方设备验证码发放、待登录状态和取消登录。完整账号授权、真实模型出图、真机安装及人脸检测仍待部署后联调。本机 Android 模拟器启动退出码为 `0xC0000005`，设备仪器测试尚未执行。

技术依据：[ML Kit 人脸检测](https://developers.google.com/ml-kit/vision/face-detection/android)、[OpenAI 图像编辑](https://developers.openai.com/api/reference/resources/images/methods/edit)、[AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。

## 服务接口

| 接口 | 说明 |
| --- | --- |
| `GET /health` | 服务状态和已启用的生成方式 |
| `GET /v1/status` | 校验应用连接码并检查生成服务配置 |
| `POST /v1/codex/login` | 获取官方登录验证码与本应用会话标识 |
| `GET /v1/codex/account` | 查询当前登录状态、邮箱和套餐 |
| `POST /v1/codex/logout` | 取消等待中的登录，或退出当前账号 |
| `POST /v1/jobs` | JSON：`requestId` UUID、`provider`（`codex` / `api`）、`original`、`reference`、`selection` PNG Base64 |
| `GET /v1/jobs/{id}` | `queued / running / completed / failed / cancelled` |
| `GET /v1/jobs/{id}/result` | 完成后的 PNG |
| `POST /v1/jobs/{id}/cancel` | 取消排队或生成任务 |

选区 PNG 与原图同尺寸，透明表示保留，不透明表示替换；服务转换成上游所需的反向 Alpha 遮罩。请求需要 `Authorization: Bearer APP_TOKEN`（当服务配置了连接码时）。

Codex 相关调用和任务另外携带 `X-Codex-Session`，其值来自登录接口的 `sessionToken`。App 会保存会话，并将任务绑定到提交时的服务地址及会话，以便重启后继续获取结果。登录页面由官方提供，应用接收验证码和登录完成状态。

任务元数据与完成图片存入 `DATA_DIR`；服务启动时清理超过 24 小时的任务记录和结果图。服务重启时未完成的任务标记为失败，可由用户重新生成；已完成图片可继续下载。Codex 的会话、账号状态和输入图片位于 `DATA_DIR/codex-sessions`；部署时挂载持久化数据目录，便于重启后恢复账号。Codex 进程空闲 15 分钟后退出，下一次调用自动恢复。
