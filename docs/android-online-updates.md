# Piko Android 自动更新：公开发行仓库

## 方案

- 开发仓库 `1062624546/piko` 保持 **Private**：源码开发、CI、签名密钥以及签名正式版构建。
- 发行仓库 `1062624546/piko-updates` 设置为 **Public**：只存每个版本的 GitHub Release 附件（签名 APK、`release.json` 和对应源码归档）。
- App 通过公开的 `/repos/1062624546/piko-updates/releases/latest` 查询版本；当 GitHub API 匿名限流时自动读取 `/releases/latest/download/release.json`。
- 所有 APK 在本地验证 SHA-256 后才交给 Android 系统安装器。仓库令牌不包含在 APK 中。

## 一次性设置（不可省略）

1. 打开 https://github.com/new ，创建 **Public** 仓库 `piko-updates`，所有者为 `1062624546`，勾选初始化 README（确保有 `main` 分支）。不需要在公开仓库放完整开发历史。
2. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens，创建到期日合理的令牌。Repository access 仅选择 `piko-updates`，Repository permissions 仅授予 **Contents: Read and write** 和必须的基础 Metadata 权限。不要把 Token 放到仓库文件或 APK。
3. 在私有开发仓库 `1062624546/piko` → Settings → Secrets and variables → Actions → Secrets，创建 `PIKO_UPDATES_TOKEN`，填入上面的 Token。签名 Secret `ANDROID_KEYSTORE_*` 沿用原有配置。
4. 在私有仓库 Actions 运行 `Android Signed Release APK`（Run workflow，选择 `main`），或在 `main` 合并触发构建。
5. 构建成功后，访问 https://github.com/1062624546/piko-updates/releases 查看 `v1.3.1`，确认 APK 和 `release.json` 已公开。再到 https://api.github.com/repos/1062624546/piko-updates/releases/latest 检查最新版本、APK、`digest`。

## 后续发版

1. 修改 `app/build.gradle.kts` 的默认版本（例如 1.3.2）和递增版本码（例如 1003002），同时修改 `.github/workflows/android-release-apk.yml` 中的 `PIKO_VERSION_NAME`、`PIKO_VERSION_CODE` 与对应的 APK 文件名。主分支提交。
2. 正式版工作流在私有仓库完成 APK 构建、签名验证；只把 APK、对应源码归档、版本 JSON 上传至公开发行仓库。
3. 客户端开屏或在“检查更新”页面执行检查；下载新版本、验证 SHA-256，提示系统安装。此流程不需要 Cloudflare Worker。

## 现有安装包说明

已经安装的 `1.3.0` 是按原作者源构建的，不具备切换到新发行仓库的能力。**首次必须手动安装新渠道的 `1.3.1` 正式签名 APK**（相同 `applicationId` 与签名可覆盖升级）。此后的 `1.3.2` 及以上才能由 App 自动发现并下载安装。

## 注意事项

- 公开发行仓库是公开下载端点，知道地址的人都能下载 APK。原始开发仓库仍私有，但发布的对应源码归档因项目 **AGPL-3.0-or-later** 的要求而公开。请在首次发版前确认源码树中没有你不愿公开的个人配置和敏感文件。
- 公开仓库发布同一版本号后不自动替换 APK。真正变更了 APK 应发布更高版本号，以避免签名或版本检查混乱。
- 如果 GitHub API 和 github.com 在某些网络环境都无法访问，此方案同样可能受网络限制；需要时可另行添加可公开访问的 CDN/镜像站点作为冗余，但不必为基本自动更新先部署 Worker。
- 不要删除项目 LICENSE 或侵犯上游 AGPL 署名/许可证义务；界面不显示社区外链不等于取消开源许可。
