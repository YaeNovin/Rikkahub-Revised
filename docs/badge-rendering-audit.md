# 徽章图片渲染审查（2026-09-10）

## 范围与已修复问题

- 用户补充截图 `D:/qq/qqliaot/Screenshot_2026_0910_012012.jpg` 后确认，上一轮修复还不完整：图片和点击区域已经生成，但所有徽章仍呈灰块。手机常用 QA 当时仍为 `2.4.8-revised.7-20260910-010743`，不包含后续修复。
- **灰块的直接原因是缺少局部绘制区域。** Coil 的 `SvgImage` 把根 SVG 宽高设为 `100%`，Compose 在屏幕画布上平移、缩放、裁切后，原生 Canvas 的宽高仍是整块画布尺寸。AndroidSVG 据此扩大徽章，局部区域只能看见放大的灰色标签底块，文字和另一半颜色被裁走。独立 `toBitmap` 测试的画布恰好就是图片大小，因此上一轮的像素检查未能覆盖这个问题。外链点击与此绘制阶段独立，能够跳转不代表图案正确。
- 现在在 SVG 图像创建时为其设置独立 `RenderOptions.viewPort`，保留矢量绘制。保存 PNG 时复制绘制选项并使用导出目标尺寸，防止影响缓存中的原图和预览。内存键升级至 `v3`，避免复用没有局部绘制区域的图像。
- **补齐内嵌 SVG Logo。** 实际 Shields.io 的 Kotlin / GitHub 徽章使用 SVG2 的 `image href="data:image/svg+xml;base64,..."`；AndroidSVG 1.4 原先将它交给位图解码器，无法显示。现将有大小、总量和嵌套层数限制的内嵌 SVG 转为保留坐标、颜色及 viewBox 的嵌套矢量节点，不增加外部网络请求。AndroidSVG 仍使用 Coil 已有的 1.4 版本，只增加显式编译依赖以访问绘制区域接口。
- 检查了 Markdown 解析、原生 HTML 分派、Coil 加载/解码、缩略图、放大预览、保存及 GitHub 仓库卡片之间的关系。
- Markdown 中的图片原本已走 `MarkdownNew`，但紧凑列表和表格单元格交给 `HtmlInlineGroup` 后只生成文字；嵌套在 `strong/span` 内的图片也会丢失。现在统一拆分图片与文字片段，保留文字格式、图片顺序和包裹图片的链接。代码中的图片示例仍是代码。不会修改共享的解析缓存。
- GFM 提示框标记和第一行正文通常在同一个段落。旧逻辑会漏识别提示框，或者删除整个首段。现在只移除 `[!NOTE]` 等标准标记，保留该段的图片、正文和后续内容。
- SVG 绘图坐标与显示尺寸混淆。实际 Badgen 返回 `width="88.6" height="20" viewBox="0 0 886 200"`；原解码结果错误采用 `886×200`。统一的 SVG 解码器现在优先读取 CSS 宽高，缺少一边时按 viewBox 比例补足，没有宽高时使用 viewBox。支持 px 和物理长度单位，不把百分比当成像素。
- SVG 不再因为聊天容器给出的大尺寸而失去原始尺寸；缩略图、徽章预览与 PNG 保存共用该策略。PNG/JPEG 等仍由 Coil 的原有解码器处理。更换内存缓存键，避免复用旧尺寸结果。
- 失败图片现在显示原因和“点击重试”，可区分 HTTP 状态码、域名解析、超时、TLS 和解码错误。手动重试跳过原内存/磁盘缓存读取，避免缓存了网关返回的错误网页后反复解码同一错误内容。
- 软件日志记录 `INLINE_IMAGE` 错误，包含来源域名、错误类别及异常类型；不记录完整签名链接、URL 中的凭据、查询参数或异常消息中的潜在密钥。
- GitHub 仓库卡片仍排除链接包裹的徽章，不会把徽章链接误替换成仓库卡片。

## 验证方式

当前结果：20 项相关 JVM 测试通过；V2301A / Android 16 上 7 项 Android 测试通过，包含软件画布、真实 GPU 画布、解码与在线徽章内容；QA Kotlin 编译通过。完整聊天页面的布局操作和点击重试不计入已通过项。

- JVM：`HtmlImageContentTest`、`GfmModernExtensionsTest`、`ZoomableAsyncImageTest`、`GitHubRepositoryLinksTest`，覆盖嵌套链接、列表、表格、标题、提示框、文档不可变性、CSS 尺寸与日志脱敏。
- `EmbeddedSvgImagesTest` 覆盖矢量 Logo、重复定义 ID、URL 编码、非 SVG 和超限数据。
- Android：`BadgeDecoderTest` 检查 PNG 解码、SVG 显示尺寸、放大后的像素，以及独立 CSS 宽高和 viewBox 的组合。
- 新增较大画布内平移和裁切的用例，在修复前真实失败、修复后通过；还验证内嵌矢量 Logo 的像素。
- `BadgeHardwareCanvasTest` 用 Android `HardwareRenderer` / `RenderNode` 在 GPU 上执行 3.5 倍缩放与裁切，再读回像素；旧工厂和新工厂通过同一管线生成对照图，旧路径可复现纯灰块。
- `BadgeNetworkProbeTest` 需显式传入 `-e badgeNetworkProbe true`，使用应用同源 OkHttp 配置读取公开 Shields.io、Badgen、Kotlin `for-the-badge`、GitHub `flat-square` 徽章，检查位图及 GPU 上的文字、颜色和 Logo，不调用模型接口。
- `BadgeRenderingRegressionTest` / `BadgeLayoutDeviceTest` 为布局与失败重试的界面回归用例。当前 V2301A 上测试页面启动未完成，不能将这些用例的编译通过视为界面验证通过；已加入超时保护。
- 本次未取得用户失败徽章的原始 Markdown/URL。常见样例通过不代表任意失效链接、远端限流或不兼容 SVG 都可显示；新的错误提示与软件日志可供定位具体样例。

本次验证使用独立 `me.rerere.rikkahub.revised.debug` 包，没有覆盖常用 QA 包或清除用户数据。

## 真机 GPU 结果图

文件位于 `app/build/reports/badge-regression/`。品红色是验证裁切边界的测试底色，不是应用背景。

- `hardware-before.png` / `hardware-after.png`：相同的灰绿双色样例；旧路径整块灰色，新路径正确分色。
- `style-0-gpu.png` / `style-1-gpu.png`：Shields.io 和 Badgen 普通徽章。
- `style-2-gpu.png` / `style-3-gpu.png`：Kotlin 胶囊样式和 GitHub 方形样式，可见文字与矢量 Logo。
