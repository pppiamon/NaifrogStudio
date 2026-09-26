# 让所有用户直接使用的通用方案

所有用户连接同一个公网服务域名，发布 APK 时内置该域名。普通用户安装后直接登录 Codex，再上传照片进行创作。

```text
用户手机 App → 固定服务域名 → 奶蛙服务 → 用户自己的 Codex 账号
```

你部署一次服务，所有用户共用这个服务入口。每个用户仍使用自己的 Codex 登录会话。服务器地址变化时，只需更新域名解析并迁移服务数据，App 中的域名可继续使用。

下面的 `api.your-domain.com` 为填写位置示例，实际需要使用你拥有并已解析的域名。目前交付包尚未绑定已上线的公网服务。

## 1. 由发布者准备服务器和域名

准备一台可持续运行 Docker Compose 的服务器，给它分配固定公网地址。服务器需能连接 Codex 的登录与生成服务。

为 `api.你的域名` 设置 DNS A 记录，指向服务器公网 IPv4 地址；有公网 IPv6 时可另设对应 AAAA 记录。让服务器的 TCP 80、443 端口能被公网访问。

## 2. 部署服务

将本项目的 `server` 文件夹上传到服务器，进入该文件夹：

```sh
cp .env.example .env
cp public.env.example public.env
```

在 `public.env` 中填写真实域名：

```dotenv
NAIFROG_DOMAIN=api.your-domain.com
```

`.env` 使用默认 `CODEX_ENABLED=true`，`APP_TOKEN` 留空，用户通过自己的 Codex 账号登录。Codex 方式的 `IMAGE_API_KEY` 留空。

启动公网版本：

```sh
docker compose --env-file public.env -f compose.public.yaml up -d --build
docker compose --env-file public.env -f compose.public.yaml logs -f gateway naifrog
```

此配置启动奶蛙服务和 Caddy 域名入口。Caddy 根据域名自动申请及续期 HTTPS 证书；服务数据与证书均使用持久化卷。[Caddy 官方说明](https://caddyserver.com/docs/automatic-https)

检查服务：

```sh
curl https://api.your-domain.com/v1/status
```

应返回 `ready: true`，且 `providers.codex: true`。在手机蜂窝网络下访问同一个地址，可检查实际公网连通性。

## 3. 把固定地址写入 APK

在本机的 `NaifrogStudio` 项目目录执行：

```powershell
.\publish-apk.ps1 -ServiceUrl 'https://api.your-domain.com' -JavaHome '你的JDK17路径' -AndroidSdk '你的Android SDK路径'
```

脚本先检查公网服务，再调用项目现有的 `-PserviceUrl` 构建参数。生成的 APK、AAB 和地址记录位于项目的 `release` 目录。

新用户安装这份 APK 后，自动连接内置地址，无需寻找 IP 或填写服务地址。继续使用同一域名时，后续版本沿用这个构建参数；发布应用商店更新前递增 `versionCode`。

已经在旧测试版中保存过局域网地址的用户，需要在「设置」中把地址更新为这个统一域名。新的首次安装用户直接使用内置值。

## 4. 服务更新

替换服务器上的源码后，在 `server` 目录重新执行同一条启动命令：

```sh
docker compose --env-file public.env -f compose.public.yaml up -d --build
```

公网入口继续使用原域名，账号和作品数据保留在持久化卷中。

## 当前准备状态

项目已具备公网部署配置、HTTPS 域名入口和内置地址打包脚本。真实服务器、域名解析、证书签发及手机公网登录，需要在实际服务器和域名确定后验证。当前电脑未安装 Docker，未在本机运行容器部署。
