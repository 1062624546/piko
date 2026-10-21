# Piko 专用 Cloudflare 中继

与 Piko 的「网络代理 → Web 代理」配套，协议 `piko-tunnel-v2`（保留旧版 v1 接口用于升级过渡）。不是 TG/MTProto、HTTP CONNECT 或通用 SOCKS 代理。
不需要 D1、KV 或 R2。v2 使用 SQLite Durable Object 防重放，只保存随机请求标识和过期时间，约 5 分钟后清理；会消耗相应 Workers/DO 额度。

## 部署

在此目录执行（需要 Node.js 22 或更新版本和已授权的 Cloudflare 账号）：

```bash
npm ci
npx wrangler login
npm test
npx wrangler deploy --dry-run
npx wrangler deploy
```

记下输出的 HTTPS Worker 地址。初次部署未配置凭证时，只有 `/health` 可访问，转发接口拒绝请求。
然后为该地址生成凭证和导入链接，例如：

```bash
node create-access.mjs https://piko-relay.YOUR-SUBDOMAIN.workers.dev
npx wrangler secret bulk relay-secrets.json
```

`relay-access.txt` 是完整的 `piko://proxy?url=...&token=...&name=...` 链接，可复制到 App 的导入框，或在安卓上打开后确认导入并启用。
生成器不会覆盖已有文件。凭证文件已被 `.gitignore` 排除；请自行保管，勿公开或上传到仓库。
如使用自有域名，先在 Worker 的 Settings → Domains & Routes 添加 Custom Domain，再使用该 HTTPS 根地址生成链接。

## 使用与管理

- 在登录页或设置页打开「网络代理」，点击「Web 代理」。
- 点击「添加 Web 代理」→ 粘贴链接 → 测试连接 → 保存，保存后自动启用。
- 点击已保存的代理，可直接重新测试、更换链接并保存，或删除代理。
- 支持多个已保存的中继，点击单选按钮立即切换；删除操作立即保存。
- 选择「不使用代理」即可关闭，保留的配置可再次启用。
- 安卓配置用 AndroidKeyStore 加密保存；桌面中继凭证交给现有 SecretVault。
- 不把凭证写入日志、更新通知或跨设备的设置同步。

## 转发范围

| 请求 | 行为 |
| --- | --- |
| 登录、网盘、分享、上传、下载、更新、MetaTube 和图片 | 选中 Web 代理后统一通过中继 |
| 原生播放器、HLS/DASH 分片、密钥、字幕和片段提取 | 经本机媒体网关统一通过中继，保留 Range |
| 中继本身、本机回环服务 | 直接连接 |

代理失败明确报错，不自动切换直连。切换代理会取消旧网络请求，新请求使用新配置。
中继支持公开 HTTP/HTTPS 地址的默认端口，拒绝本机、内网地址及 IP 字面量；不绑定私有网络。

## 凭证轮换与吊销

Worker Secret `RELAY_TOKENS` 的内容是一个 JSON 对象，键为名称、值为随机凭证：

```json
{"owner":"至少24位的随机字母数字或下划线短横线"}
```

可添加多个名称及凭证（最多 100 个）。要吊销某个凭证，移除对应项后重新设置 Secret；要轮换，先加入新凭证，更新 App 后再移除旧凭证。
整个配置写成 `relay-secrets.json` 的结构为 `{"RELAY_TOKENS":"JSON对象的字符串"}`，通过 `wrangler secret bulk` 上传。
不必重新编译 APK。删除 App 内配置只删除本地记录，服务端吊销需更新 Secret。

## 实现与验证

- `/health`：进程健康检查，公开，不代表凭证有效或上游可达。
- `/v1/check`：需 `X-Piko-Relay-Token`，检查凭证并无登录信息地探测 PikPak API。
- `/v1/relay?url=...`：需凭证，支持公开 HTTP/80、HTTPS/443 地址及常用读写方法。
- 向上游发送前移除中继凭证，保留业务请求头，移除中继凭证、逐跳头及内部转发头。
- 每次上游跳转重新校验域名，跨源只保留安全读取头，不重放写操作；不把跳转地址交回客户端。
- 响应直接流式转发，不缓存登录信息或媒体，不记录带签名的 URL。为此默认关闭 Workers 请求日志。
- `npm test` 覆盖鉴权、公开地址边界、Range、流式响应、跨源跳转与写入重放保护。

连接测试通过不等于已验证真实账号登录或完整视频播放；后两项需在安装新 APK 后实测。

## v2 加密隧道（App 1.4.2 起）

App 只发 `POST /v2/tunnel`，无目标 URL 查询参数，无业务 Authorization/Cookie 或代理 token 请求头。
HTTP 外层仍有 Host、Content-Type、User-Agent 等传输必需字段；中继域名、流量大小与时序不会隐藏。
目标地址、方法、业务请求头、URL 参数、正文及响应状态、响应头、正文均封装在认证密文中。
不回退到 v1 或直连。连接测试也使用 v2。原导入链接与凭证不变。

- 请求前缀：`PIKO2\n`（6 字节）+ token 的 SHA-256 前 16 字节（公开路由标识）+ 32 字节随机 salt。
- 请求 key：HKDF-SHA256，IKM=token UTF-8，salt=请求 salt，info=`piko-tunnel-v2/request`，输出 32 字节。
- 响应前缀：32 字节独立随机 salt。响应 key 的 HKDF salt 为请求 salt 拼接响应 salt，info=`piko-tunnel-v2/response`。
- 每条记录：4 字节大端密文长度 + AES-256-GCM 密文（含 16 字节 tag）。明文为 1 字节类型 + 最多 65536 字节数据。
- 类型 0 为 JSON 元数据，只允许首条；1 为中间数据；2 为最后数据（可为空）。没有合法结束记录不能视作完整响应。
- GCM nonce：4 字节零 + 8 字节大端序号，从 0 起。AAD：协议 magic + 8 字节序号 + 4 字节密文长度。
- 序号不能超过 2^32-1；每次请求新 salt，响应独立新 salt，密钥按方向隔离。拒绝修改、错序及截断的记录。
- 请求元数据含毫秒时间戳，仅接受与服务端相差 120 秒内的请求。每个凭证独立 DO 原子登记 salt，先登记后转发，失败时也不重放。
- 大文件逐条处理，不全量缓冲。已认证的数据可边接收边使用；未完整读取的流不能证明整体完成。

这是基于高熵预共享凭证的应用层加密，没有独立的前向保密能力，仍依赖外层 HTTPS。
Worker 必须解密再转发，Cloudflare 平台及能修改 Worker 的管理员仍能访问明文。
凭证或已解锁设备被控制时无法保证保密。不要公开导入链接。

`npm test` 包含 Node Web Crypto 与 JVM/JCE 共用固定向量，以及篡改、过期、重放、方向隔离、大文件分块和 Range 测试。

v2 的流式加解密放在每个凭证对应的 Durable Object 内执行，入口 Worker 只路由密文，避免大视频触发免费 Worker 的短 CPU 限制。DO 在视频传输期间持续活跃，会消耗请求、活跃时长及存储额度；不是无限免费流量服务。达到平台额度时会报错，不降级直连。
