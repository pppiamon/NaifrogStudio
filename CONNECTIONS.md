# 自动连接方式 · 1.4.0

手机 → HTTPS 入口 → 电脑上的 Cloudflare Tunnel → 本机奶蛙服务 → Codex。

电脑服务仅监听本机 127.0.0.1:18787，由电脑主动建立出站通道。手机访问 HTTPS 域名，校园网的设备互访能力、两端是否在同一个 Wi-Fi、旧局域网 IP 都不参与这条连接。

## 当前电脑共享试用

向朋友发送 `手机连接/分享给朋友.txt` 中的完整邀请链接。`/pair` 页面提供 APK 下载和连接按钮，APK 由 `PUBLIC_APK_PATH` 指向，下载接口为 `/download/naifrog.apk`。朋友连接后登录各自账号，无需配置电脑服务。

App 可导入完整 HTTPS 邀请链接、naifrog 深链接或原始连接码，验证服务身份和状态成功后才更新配置；验证失败保留原连接。账号页增加邀请分享入口，使用最近实际连通的入口。

升级安装包时，已经手动连接其他服务的用户保留原连接。临时网关错误也会触发登录查询重试。

## 启动与恢复

双击交付目录的 `启动奶蛙自动连接.cmd`。电脑保持开机、联网，连接进程保持运行。服务会生成 `手机连接/connection.json`、连接码、二维码和 HTML 连接页。

当前个人试用 APK 已内置生成时的连接资料，覆盖安装会将旧局域网设置切换到 HTTPS。无需在手机端配置 VPN 分应用规则。

电脑连接通道重启后，临时 HTTPS 域名会更新。打开 `手机连接/手机连接.html`，手机扫码，再点击“打开奶蛙并连接”；也可在 App 设置中粘贴连接码。同一电脑的账号会话和未完成作品会保留。

## App 如何恢复连接

- 尝试系统默认连接、直连，以及系统提供且允许使用的 Wi-Fi／移动网络连接。
- 支持连接资料中配置多个 HTTPS 入口；各入口应指向同一个奶蛙服务。
- 在发送账号或作品请求前验证服务标识，入口变更后继续关联同一服务。
- 登录请求携带稳定的请求编号，服务端合并重复请求，避免网络重试重复签发验证码。
- 登录状态查询短时失败会继续重试并保留验证码；生成期间断线会继续查询原任务。
- 生成任务沿用原 requestId，同一任务重传不会重复排队。

Android 可使用的底层网络取决于 VPN 客户端和系统配置；主连接使用系统默认的 HTTPS 网络。没有可用互联网、电脑关机或服务停止时，App 保留任务并显示连接状态。

## 以后固定域名发布

同一套服务使用现有 `compose.public.yaml` 和 Caddy 部署。以实际固定 HTTPS 域名运行 `publish-apk.ps1` 即可构建内置该地址的 APK；HTTPS 的自动重连功能同样生效。固定域名保持不变时，用户无需更新连接码。

如需预设多个入口，将包含 version、serviceId、endpoints、token 的连接资料保存为 connection.json，使用 `build-release.ps1 -ConnectionFile <文件路径>` 构建。serviceId 从服务 `/health` 返回值获取，endpoints 填写实际 HTTPS 入口数组，token 对应服务 APP_TOKEN。

server/data-connected 是当前自动连接试用服务的数据目录，应保留；更换入口不会更换服务标识。正式部署中的服务标识保存在 DATA_DIR/service-id。多个入口应代理到同一运行实例。

## 验证范围

- 21 项 Android 组件测试：备用入口、连接码导入、旧设置迁移、服务标识匹配、切换地址后账号及任务保留等。
- 21 项服务测试：登录请求去重、服务标识重启保留、任务及图像处理回归。
- 真实 HTTPS 入口：分别通过直连、电脑当前的 VPN HTTP 代理访问成功；通过 HTTPS 获取两组不同的官方 Codex 设备验证码，确认两位用户会话独立，查询待登录状态后取消测试登录。
- Android 的物理网络绑定在 JVM 测试中替代为真实本地 HTTP 连接；手机上的 VPN/Wi-Fi/流量切换需要在安装后的设备上确认。

Cloudflare 临时通道用于本次试用，正式发布使用固定 HTTPS 服务域名。[Cloudflare 官方说明](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
