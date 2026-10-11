# 参与贡献

本仓库是独立维护的 Android 修改版。问题和 PR 请提交到 [1062624546/piko](https://github.com/1062624546/piko)，并说明 Android 版本、App 版本与复现步骤。

修改按 [AGPL-3.0-or-later](LICENSE) 提供，保留已有版权和许可证声明。本分支不要求贡献者签署上游 CLA；若向原项目提交贡献，应遵循原项目自己的贡献流程。保留的 CLA.md 是上游历史文档，不代表本分支要求签署。

验证命令：

```bash
./gradlew :shared:desktopTest :ui:desktopTest
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -Ppiko.enableAbiSplits=false
```

共用业务测试仍使用 JVM target，不表示本仓库提供桌面产品维护。界面和播放器行为还需在 Android 真机验证。

正式发布详见 [Android 在线更新说明](docs/android-online-updates.md)。
