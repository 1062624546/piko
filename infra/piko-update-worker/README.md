# Piko 私有仓库更新接口

仓库 1062624546/piko 目前为 Private。匿名访问它的 GitHub Releases API 通常返回 404，故 APK 无法直接使用私有仓库 API 查询更新。本 Cloudflare Worker 通过服务端 Secret 查询版本和转发 APK，并对外暴露公开只读接口。

## 配置与部署

1. 在本目录执行 `npx wrangler deploy`，完成 Cloudflare 登录和部署。
2. 执行 `npx wrangler secret put GITHUB_TOKEN`，填入只能读取该私有仓库的最小权限细粒度 GitHub Token。
3. 测试 `https://你的-worker域名/latest` 是否返回 `tag_name`、`assets` 和 `digest`（应为 sha256:...）。若无摘要，Piko 会阻止安装。
4. GitHub Settings → Secrets and variables → Actions → Variables，新建 `PIKO_UPDATE_METADATA_URL`，值为 `https://你的-worker域名/latest`。
5. 设置地址后**重新构建正式 APK**，新编译的包才会接入你的 Worker。已经安装的包不会自动获得这个地址。

## 公开接口

- `GET /latest`：返回与 GitHub latest Release 相同字段的 JSON。版本来自你的私有 Releases，APK 地址替换成当前 Worker。
- `GET /assets/:id`：通过服务端 Token 获取并流式转发 APK。不泄漏 GitHub Token 到 APK 或设备。

注意：此 Worker 的 APK 下载对任何知道接口地址的人公开，不是用户登录后才可下载的私密接口。原项目 AGPL-3.0-or-later 许可证与必要的源代码提供义务须保留。
