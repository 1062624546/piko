# Piko Android 修改版

本项目基于 [NihilDigit/piko](https://github.com/NihilDigit/piko) 修改，由 [1062624546](https://github.com/1062624546) 独立维护，**并非原作者官方版本，也非 PikPak 官方客户端**。

本仓库仅维护 **Android 手机版本（Android 8.0 及以上）**。上游支持 Windows、macOS 和 Linux；本修改版不提供这些平台的维护、构建和安装包。仓库保留部分共用及桌面代码，以便复用业务逻辑和跟进上游。

## 功能

- PikPak 网盘文件管理、上传、下载和视频播放。
- 底部「分享」入口：输入分享链接，查看分享浏览历史并恢复目录。
- 分享目录浏览、当前文件夹搜索、当前目录多选和转存。
- 分享视频临时预览及清理，不记录分享视频观看进度。
- 分享图片预览与原图加载。
- Android 在线检查更新、下载 APK 和 SHA-256 校验，安装由 Android 系统确认。

实际行为以发布版本为准。网盘服务能力与限制由 PikPak 提供。

## 安装与更新

从本仓库的 [Releases](https://github.com/1062624546/piko/releases) 下载正式签名 APK。文件名为 `piko-版本号-universal.apk`，下载后直接安装，无需解压。

首次从旧更新渠道迁移时，请手动安装接入本仓库更新源的 1.3.1 或更新版本。之后可在 App 的「检查更新」中下载新版本；正式版也支持启动时检查。覆盖安装需要相同包名和签名，后续发版沿用原签名密钥。

Debug APK 用于测试，不作为正式更新包。

## 在线更新与开发

版本查询使用 `https://api.github.com/repos/1062624546/piko/releases/latest`，失败时尝试本仓库最新 Release 的 `release.json`。客户端不携带 GitHub Token。

主分支的 Android 代码或正式发布工作流变更会触发测试、正式签名构建和发布。仓库凭据由 GitHub Actions 提供，签名密钥通过 Actions Secrets 配置。

详见 [Android 在线更新说明](docs/android-online-updates.md) 和 [贡献说明](CONTRIBUTING.md)。

## 修改记录

- 1.3.0：底部导航新增分享入口，接入分享浏览历史。
- 1.3.1（发布状态见 Releases）：修复分享图片预览；更新渠道改为本仓库；改为仅维护 Android。
- 1.3.2（发布状态见 Releases）：修复分享图片预览顶部栏与手机状态栏重叠。
- 本轮修改日期：2026-10-11。更完整的修改记录见 Git 提交历史与 Release 说明。

## 许可证与致谢

本修改版按 [AGPL-3.0-or-later](LICENSE) 发布，保留上游版权声明及许可证。原项目 README 标明，1.1.0 及更早版本曾使用 MIT；本修改版以当前代码的 AGPL 许可为准。

发布 APK 时提供对应源码归档和构建文件。开源许可允许合规商业分发，也赋予接收者依法使用、修改及再分发的权利。

- 原项目及主要实现：[NihilDigit/piko](https://github.com/NihilDigit/piko)。
- PikPak SDK：[NihilDigit/pikpak-kotlin](https://github.com/NihilDigit/pikpak-kotlin)。
- PikPak 接口实现参考：[52funny/pikpakcli](https://github.com/52funny/pikpakcli)。
- 功能设计参考：[digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master)。
- MediaMP：[open-ani/mediamp](https://github.com/open-ani/mediamp)。
- Android 播放器包含以 GPLv3 构建的 mpv 与 FFmpeg；保留第三方组件许可证和对应源码、构建来源信息。
