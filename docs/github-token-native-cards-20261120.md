# GitHub Token 与原生仓库卡片

2026-11-20，在 `.11` 发布后新增。记忆和分支的独立审阅见[问题清单](memory-branch-review-20261120.md)。

同日后续改为 [OG 风格横版卡片](github-social-preview-20261120.md)，新增真实语言分布、标准 1280×640 导出和大字体布局；以下保留 Token 与基础交互说明。

## 设置入口

“设置 → 常规设置 → GitHub 仓库卡片”提供新版/原有样式切换、Token 填写/替换/移除、主动验证、GitHub 官方获取按钮，以及匿名和鉴权的每小时本地请求预算。

- 默认使用新版 Compose 原生卡片：所有者头像、仓库标题、可见性/归档状态、简介、Language、Issues & PRs、Star、Fork 和底部语言色条；关闭“新版原生卡片”切回原有样式，原有样式保留许可证等紧凑信息。
- 推荐 fine-grained PAT，获取入口为 `https://github.com/settings/personal-access-tokens/new`。读取私有仓库时选择相应仓库并允许 Metadata read；本功能不需要仓库写入权限。
- 匿名预算默认 50，可设置 1–60；鉴权预算默认 4500，可设置 1–5000。GitHub 官方通常按出口 IP 给匿名请求每小时 60 次、个人 Token 每小时 5000 次，且与其他客户端共享；服务端响应头和冷却仍有优先权。
- 预算和服务端冷却按匿名身份或 Token 的 SHA-256 指纹隔离，复用同一 Token 不重置用量。提高本地预算不会清除服务端冷却。
- 最近请求可显示本地用量、服务端剩余额度和重置时间。主动验证读取本项目的公开仓库一次，跳过元数据缓存，但仍遵守本地预算与服务端冷却。

## 凭据边界

Token 通过 AES-256-GCM 加密。随机 IV 和认证标签保护密文完整性，密钥保存在 Android Keystore，不可导出；是否由安全硬件承载取决于设备，不承诺所有 ROM 都支持 StrongBox。
密文保存在应用的 `noBackupFilesDir`，不进入普通 Settings、Room、Web 配置、应用备份 ZIP、Android 系统备份或文件分享路径。输入框使用掩码，不回填已有明文，不使用 SavedState 保存输入。

鉴权头只由独立客户端发往固定 HTTPS `api.github.com` 的仓库 API。保留重定向校验与总跳数限制，不转发到网页、头像 CDN、模型供应商、聊天上下文或日志。API 原始响应可能含临时凭据，只解析白名单显示字段，禁止整体保存或记录原始响应。

密钥不可用或密文损坏时明确提示重新填写/移除，不回退到明文存储。Token 替换/移除会更新凭据修订号，旧请求结果不能回填当前卡片。401 会清除私有内存缓存并阻止自动重复请求，替换 Token 或主动验证后再恢复。

## 私有仓库与回退

- 填入有权限的 Token 后，仓库主页链接可以显示私有仓库的同类元数据；不加载 README、文件、Issue/PR 正文或执行写操作。
- 私有元数据仅在当前凭据会话的内存中保留，最多 50 项，不写入 Room。换 Token、移除 Token、鉴权失败或进程退出后需要重新读取。
- 私有卡片显示 Private。分享图片和保存相册只由用户明确操作触发，生成的图片包含当前卡片内容，应按私有信息处理。
- 未填 Token 时继续匿名读取公开仓库；私有/不可见仓库的 404 不推断其是否存在，保留仓库名、错误提示和网页跳转。
- 401 鉴权失败、普通 403 权限拒绝、本地预算、服务端主额度、次级限流，以及网络/DNS/TLS/解析失败分别提示。429 或有证据的限流 403 遵守 Retry-After / X-RateLimit-Reset，不自动重试循环。

## 交互和主题

短按打开仓库网页；长按仅提供“分享”“预览卡片”“保存至相册”三项。分享导出当前原生卡片为 PNG 并打开系统分享选择器，不自动选择接收方。相册保存使用现有 Android 存储权限流程。预览复用当前元数据，不产生新的 GitHub API 请求。

新版和原有卡片使用当前 Material 文字、强调色以及富内容容器/边框设置，支持深浅色与壁纸取色。语言色条使用 GitHub Linguist 的常见语言配色，未知语言回退主题描边色；头像仅接受 GitHub 官方头像 CDN 的标准用户路径，不携带 Token。

## 验证范围

独立 Debug 包名为 `me.rerere.rikkahub.revised.githubqa`，使用 `scripts/github-cards-qa.init.gradle` 构建。测试只使用合成 Token 和仓库夹具，不读取或改写常用 QA/正式版的凭据与聊天数据。

- 本机 37 项 JVM 回归通过，覆盖 URL/链接识别、匿名兼容、鉴权头、预算身份隔离、超出原 50 次限制的鉴权请求、401/403 与主/次级限流、私有缓存隔离、移除 Token 和在途响应丢弃，以及 AES-GCM 随机 IV 和密文认证；OG 后续新增语言分布缓存、预算和数值校验用例。
- vivo V2301A / Android 16 上，实际 Keystore 加密、重建存储实例后的解密、不可导出密钥和密钥缺失后的拒绝读取通过。
- 新旧卡片的深浅色截图、短按回调、三项长按菜单和只读预览通过；预览复用元数据，当前 OG 夹具只产生元数据和语言各一次 API 调用。
- 实测并修复 GPU 硬件截图直接绘制到软件 Canvas 时的导出失败。转换为可绘制的 ARGB 位图后，相册 PNG 可解码、系统分享选择器可打开；没有选择接收方或实际发送图片。
- Token 设置的填写、保存、移除及官方获取按钮通过实际界面用例；测试结束清除合成凭据、相册和分享缓存图片。亮屏设置恢复原值。
- 手机进行真实匿名公开元数据和语言请求各一次，Room 缓存及订阅验证通过；真实个人 Token 和真实私有仓库联调尚未执行，不将合成鉴权测试视为真实权限验证。

卡片内部退出正文的文字选择范围，防止长按菜单和选区抢占手势；最终回归包含正文选择容器下的卡片。
壁纸取色复用现有主题链路，本轮没有遍历全部外观组合。没有重跑与本功能无关的完整 Lint 和全应用压力测试。

可复现命令（指定实际设备序列号）：

```sh
python scripts/build_qa.py :app:assembleDebug :app:assembleDebugAndroidTest --init-script scripts/github-cards-qa.init.gradle --offline
python scripts/qa_github_cards.py --serial DEVICE_SERIAL --install
```

增加 `--network-probe` 可执行元数据和语言各一次真实匿名公开 API 读取。设备日志、PNG 和构建报告保存在忽略的 `build/reports/`，不作为用户数据提交。

## 官方依据

- [GitHub REST 限流](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [获取仓库及 Metadata read 权限](https://docs.github.com/en/rest/repos/repos#get-a-repository)
- [个人 Token 管理](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [GitHub Linguist 语言定义](https://github.com/github-linguist/linguist/blob/main/lib/linguist/languages.yml)

GitHub 限流、仓库权限与 Android Keystore 文档已通过 AnySearch 读取核对。
