# GitHub 仓库卡片审阅与链接交互修复

核对日期为 2026-11-20。卡片外观和 Token 部分是后续方案，本次代码修改针对 Android 聊天中的链接点击、引用来源和网页预览。

## 当前卡片

| 范围 | 实现与边界 |
| --- | --- |
| 识别 | 助手正文中的 GitHub 仓库主页，最多转换六处，保留周围的列表、表格与行内格式。代码、徽章图片、用户消息和仓库子路径使用原有显示路径。 |
| 显示 | Compose 卡片显示仓库名、简介、Star、Fork、主要语言、许可证与归档/派生状态，使用应用的富内容主题。当前没有 Primer 风格选项。 |
| 加载 | 卡片进入可见区域停留 350ms、应用在前台且流式结束后刷新。导出读取本地缓存。 |
| 缓存 | Room 最多 500 条，成功缓存 30 分钟，支持 ETag、旧数据和失败重试。所有卡片共享串行请求队列。 |
| 鉴权与额度 | 独立 OkHttp 客户端访问公开 REST API。当前没有 GitHub Token 配置，另有每小时 50 次的本地预算及持久化服务端冷却。 |

主要入口为 [GitHubRepositoryCard](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/GitHubRepositoryCard.kt)、[链接转换](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/GitHubRepositoryLinks.kt)与[仓库数据层](../app/src/main/java/me/rerere/rikkahub/data/github/GitHubRepository.kt)。已有完整规则见[卡片指南](github-repository-cards.md)。

## GitHub 原生风格的实现方式

可以用现有 Compose 卡片复现 GitHub 仓库列表与置顶仓库的结构，数据继续来自官方 REST API。建议采用 6px 左右的圆角、细边框、仓库图标、蓝色仓库名、Public/Archived 等小标签、简介和带语言色点的统计行。图标可使用具有明确许可的 Octicons 资源，并记录来源。样式数值和颜色映射以固定版本的 Primer token 为依据。深浅色、壁纸文字配色、大字体和长仓库名需一起验收。[Primer 颜色说明](https://primer.style/product/getting-started/foundations/color-usage/) · [Octicons](https://primer.style/octicons/)

建议先提供紧凑与展开两种密度。最新更新时间、Topics、默认分支和仓库主页可从已有的仓库响应中补充，通常不增加请求次数。Release、Issue 与 PR 卡片需要各自的数据和缓存，适合后续按需加载。重复仓库链接可以只保留首张展开卡，其余位置显示紧凑链接，减少长回答占用。

GitHub 仓库的社交预览图适合作为可选的图片封面。它是静态图片，主要用于分享传播。可交互的 Star、Fork、语言和打开操作仍应由 Compose 呈现，并使用已经缓存的结构化字段。自定义社交图片由仓库维护者管理。[GitHub 社交预览说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview)

## 可选 GitHub Token

可以增加“设置 → 常规设置 → 链接预览 → GitHub”入口，提供 Token 的填写、替换、删除和主动验证。默认仍可匿名读取公开仓库。公开 GitHub REST 请求通常按出口 IP 每小时 60 次计量，个人 Token 的主额度通常为每小时 5,000 次，并与该用户的其他客户端共享。服务器还有独立的次级限流，网络连通性和权限也会影响加载。[GitHub REST 额度](https://docs.github.com/rest/using-the-rest-api/rate-limits-for-the-rest-api)

建议按以下顺序实施。

1. 使用本机 Android Keystore 保护 Token，加密值保存在专用本地存储中。需要同时检查系统备份规则、应用导出和 Web 配置接口的边界，输入框只展示掩码。
2. 由仓库客户端向固定 HTTPS GitHub API 添加 Bearer 头，保留现有重定向校验。供应商请求、模型上下文、网页预览和日志使用各自的数据路径。
3. 优先面向公开仓库提供鉴权提额。官方获取仓库端点支持 fine-grained PAT，所需仓库权限为 Metadata read。当前解析器要求 private=false，可以继续保留这个范围。[获取仓库与权限](https://docs.github.com/en/rest/repos/repos#get-a-repository)
4. 同步调整每小时 50 次的本地预算，展示鉴权状态、剩余额度与重置时间。额度和冷却按匿名出口或 Token 身份管理，替换/清除 Token 时切换对应状态，缓存中的失败提示应能重新验证。
5. 增加 401、权限不足的 403、主额度耗尽及次级限流的可区分提示。验证 Token 由用户主动触发，使用一次读取公开仓库的请求并展示结果。

先落地 Primer 风格和 Token 提额，再按使用需求扩展 Release、Issue/PR 与社交封面。私有仓库支持需要另行定义可见性、缓存、图片导出和备份行为。

## 本次链接修复

- 正文链接使用独立的短按与长按判定。短按在抬指时打开一次，长按完成触摸序列后打开菜单，滚动取消点击。命中区域按实际字形计算，并限制内联链接的触摸扩展，减少与附近文字选择争抢触点。
- 引用角标自身文字退出正文选择范围，保留周围正文的原生选择。
- 搜索工具长输出被截断时，先将 id、url 和 title 写入本地消息元数据。角标读取兼容原有完整 JSON，并跳过异常条目。已经缺失来源的旧消息会显示提示，需重新搜索取得地址。
- 点击引用统一使用聊天链接入口。链接菜单增加“预览网页”，通过聊天持有的 WebView 弹层显示，关闭后回到原列表位置。外部打开失败时依次尝试系统处理和应用内网页回退。
- 统一处理 www 地址、首尾空白和网页路径空格。来源标题包含不完整百分号转义时仍可显示原始标题。

## 本轮验证

- 应用与测试 APK 编译通过。八个定向测试类共 56 项 JVM 回归通过，覆盖 GitHub URL/卡片、引用解析与序列化、网页地址规范化、WebView 路由和本地资源策略。
- USB 连接的 V2301A / Android 16 上使用独立 Debug 包 `me.rerere.rikkahub.revised.linkcitationqa` 和合成内容。原生 Markdown 与 HTML 路径分别完成深浅色四组验证，每组包含四个来源角标点击、长按、流式完成和滚动后内容检查。
- ADB 驱动生产 LinkText 和 SelectionContainer 验证连续短按每次触发一次、长按链接菜单、四次附近正文长按后继续点击，以及从链接开始滑动时正常滚动。
- 链接菜单中的“预览网页”成功在应用内 WebView 加载 example.org。返回后保留原位置，链接继续响应。“打开链接”启动了外部浏览器，返回后状态可继续使用。
- Compose 仪器测试停在测试界面启动阶段，本轮未计为通过。改用 ADB 完成上述设备断言。临时预览夹具第一次缺少 Navigator，补全夹具上下文后通过；该临时源码已恢复，夹具和结果保存在忽略的 build 目录。

设备结果与截图保存在本机 `build/reports/link-citation-device/`。既有常用 QA 与正式版使用各自包名，本轮验证使用独立 Debug 安装。
