# Android 在线更新与发布

## 更新渠道

开发、源码及正式发布统一使用公开仓库 `1062624546/piko`。不需要 `piko-updates`、额外个人 Token 或 Cloudflare Worker。

- 首选查询：`https://api.github.com/repos/1062624546/piko/releases/latest`
- 备用查询：`https://github.com/1062624546/piko/releases/latest/download/release.json`
- 客户端不携带发布凭据。APK 下载后验证 SHA-256；Android 系统负责安装确认和签名匹配。

GitHub 不可访问时，检查及下载仍会受网络限制。

## 一次性配置

保留 Actions Secrets 中的 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。后续版本必须沿用相同签名才能覆盖安装。

工作流使用 GitHub 自动提供的 `GITHUB_TOKEN`，声明 `contents: write`，发布到当前仓库，无需创建 `PIKO_UPDATES_TOKEN`。

## 正式发布

1. 在 `app/build.gradle.kts` 修改默认版本名与递增版本码，同时更新 `.github/workflows/android-release-apk.yml` 的版本环境变量和 APK、mapping 文件名。
2. 提交到 `main`；也可在 Actions 中手动运行 `Android Signed Release APK`，选择 `main`。
3. 工作流运行 Android 单测，构建通用正式 APK，验证签名，打包对应源码。
4. 以实际构建提交创建草稿 Release，上传 APK、对应源码和包含 APK SHA-256 的 `release.json`，附件完整后公开。
5. 同一正式版本发布后不会自动替换 APK；代码改变应发布更高版本号。

只构建和发布 Android。旧多平台 Release 与上游 CLA 自动化已从本分支移除，共用 JVM 测试仍保留。

## 旧版本迁移

已安装的 1.3.0 指向旧更新渠道。需要手动安装一次 1.3.1 正式版（相同包名和签名），后续版本才能通过本仓库在线更新。Debug APK 不参与正式版覆盖升级。

## 源码与许可证

保留 AGPL 许可证、原项目及第三方版权声明。每个 APK 配套对应源码归档，仓库保存必要构建文件及第三方组件来源。公开发布不包含 Actions Secrets 中的签名密钥。
