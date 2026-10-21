# Piko 专用 Cloudflare 中继

与 Piko 的「网络代理 → Web 代理」配套，协议 `piko-relay-v1`。不是 TG/MTProto、HTTP CONNECT 或通用 SOCKS 代理。
不需要 D1、KV、R2、Durable Objects 或定时任务。

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
