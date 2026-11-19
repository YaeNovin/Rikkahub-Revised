# 历史消息、XML/SVG 与诊断日志更新

## 实现范围

- 历史消息预加载改为可见窗口之前 2 条、之后 2 条，靠近窗口的优先。正则转换、Markdown/Diff 解析和 HTML 文档准备在后台工作线程执行；滚动方向变化取消过期排队工作。不在后台创建 WebView，也不提前启动 GPU 渲染。
- 每轮原文预算 256 Ki 字符；正则结果缓存、Markdown AST 和 HTML 文档缓存都有条数/总字符容量限制。单条超大消息不进入预加载队列，进入视口后仍可正常解析。
- 复用预加载的视觉正则结果；Diff 分块不再在 Compose 主线程同步计算。无 GitHub 链接的 HTML 消息不再重复执行仓库卡片装饰。
- XML 声明、DTD、注释、CDATA 与后续根元素作为完整源码保存，保留空行、大小写与嵌套 SVG。普通 XML 为源码，不转成可执行 HTML；实际 SVG 继续使用受限静态预览。HTML 中嵌套 SVG 保持外层 HTML 的原始源码。已有 fenced/inline/indented code 不被重写。

这里的后台化是**移出 UI 主线程**，不是另建 Android 进程。独立进程不能承载 Compose UI，且会引入跨进程传输大段消息的额外成本。此修改不保证所有 GPU、超长消息或自定义正则场景都没有卡顿。

## 日志浏览

- 请求：供应商列表 → 请求列表 → 原有请求详情底部面板。供应商名称和端点 origin 一起分组，避免不同端点混在一起；关闭正文记录后仍保留不带路径/查询参数的 origin。
- 日期、模型、状态均为下拉菜单。日期包括全部、24 小时、7/15/30 天及原生 Android 起止日期选择。自定义范围包含结束当天，按设备时区计算，覆盖夏令时边界。
- 模型下拉展示已调用的模型 ID，并尽可能显示当前配置中的模型名称。已移除的模型仍可筛选。
- 行展示：模型 ID、9 位小数时间、请求类型、状态和 HTTP 状态码、首字 ms、耗时 ms 与测量阶段。
- 软件/系统：类型、时间、状态列表；点击展开可选择、水平滚动的等宽技术日志和堆栈。支持日期、类型、状态筛选及错误分析。保留原有 AI 分析及分析历史入口。

### 指标语义

- `preciseTimestamp`：ISO-8601 设备墙上时钟。9 位显示格式不代表设备真的具备纳秒准确度；旧数据的毫秒部分补零显示，页面和导出均有提示。
- `firstTokenMs`：沿用聊天生成链路的首个模型内容计时，单位 ms、可含小数。没有对应内容计时的请求为 `null` / `—`，不能用响应头耗时冒充。
- `durationPhase`：`headers`（响应头，进行中）、`body_complete`（响应体读取完毕）、`closed`（提前关闭，未确认完成）、`failed`（失败）、`model_complete`（模型生成链路结束，失败由 error 标记）。旧记录没有阶段。
- HTTP 成功但未读取完成的请求不进入“成功”筛选；网络/模型后续失败会更新错误状态。记录 ID 不变，补写指标不丢失响应正文。

## 渲染错误

接入声明式图形编译错误、Markdown/HTML 解析错误、WebView JavaScript 错误、加载/资源错误、渲染进程退出、加载超时及图片导出失败。归入软件/系统的 `RENDER` 类型。

后台队列最多 32 项，同一错误 30 秒内去重，去重表最多 128 项；消息/堆栈限长并屏蔽常见密钥。不记录整份绘图源码或完整资源 URL。队列过载会丢弃新增项，避免故障风暴拖垮主线程。没有增加 JavaScript 原生桥，也没有放宽 CSP/资源权限。

## 导出

- 当前筛选或所有日志：UTF-8 BOM、RFC 4180 转义的 `.csv`，包含关键列及详细 JSON 列。即使筛选只有 1 条，批量导出仍为 CSV。防护以 `= + - @` 等开头的表格公式。
- 单条详情：`.log`，包含版本、导出时间、记录 ID、时间/状态、计时语义以及分段的请求头/正文、响应头/正文、错误和堆栈。
- 脱敏/完整两个选项保留；完整模式仍屏蔽常见密钥。技术错误文本可能含个人内容，分享前仍需人工检查；未记录或已截断的正文不能在导出时恢复。

保留策略未改：请求最多 500 条 / 32 MiB；错误 7 天 / 200 条；普通事件仅本次运行最近 100 条。日期筛选不会恢复已清理记录。

## 验证

- 定向 JVM 回归：77 项通过，覆盖预加载窗口、XML/SVG、HTML 安全边界、模型计时、旧日志兼容、存储、日期范围、状态筛选、CSV 转义与密钥屏蔽。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功，使用 `scripts/appearance-regression.init.gradle` 的独立包名，不覆盖用户 QA。
- 真机：vivo V2301A / Android API 36；`DiagnosticsRegressionTest` 两项通过：供应商下钻、原生日期范围、模型/错误筛选、请求详情、渲染堆栈展开；固定 60 条富文本消息上下各 8 次滚动。没有真实模型请求。截图已检查，报告在 `app/build/outputs/diagnostics-regression/instrumentation-rerun.txt`。
- 首轮真机测试发现夹具同名节点匹配和缺少 CompositionLocal；已修正夹具后重跑。这些失败不作为应用功能或帧性能结论。

不把 JVM 通过等同于所有 WebView/GPU、主题和 Android 版本均已验证，也不把一次 Debug 帧统计称为相对旧版本的性能提升。

### 帧统计的边界

- Compose 自动化手势：46 帧全部超时，受虚拟测试时钟/批量手势驱动影响，不用于实际流畅性判断。
- 追加 `RealClockHistoryRegressionTest`（不使用 Compose test rule）：系统时钟 + 从无障碍 UI 树取得滚动区域的真实 ADB 手势，1 项通过。相同 60 条 Markdown/表格/代码消息、浮动顶栏、无自定义背景，向上/向下各 8 次。
- 单次 Debug 结果：360 帧；按当前 frame-deadline 口径 24 帧超时（6.67%）；P50 13 ms、P90 20 ms、P95 29 ms、P99 150 ms；GPU P95 3 ms，Slow UI thread 22。说明仍有长帧，不能声称卡顿已完全解决。没有旧版本对照，不能计算改进百分比。legacy jank 口径与当前 deadline 口径不同，未混用。
- 原始报告：`app/build/outputs/diagnostics-regression/device/diagnostics-regression/real-clock-gfxinfo.txt`、`real-clock-framestats.txt`；真实手势结果：`app/build/outputs/diagnostics-regression/real-clock-instrumentation.txt`。
- 20 秒 Perfetto 原始轨迹：`app/build/outputs/diagnostics-regression/history-real-clock.pftrace`。本轮仅根据 gfxinfo 解读统计，未做逐片段 trace processor 分析；具体长帧根因仍需进一步定位。
- 本轮未覆盖所有背景/玻璃材质、超长单条消息、WebView 动态图形或旧型号设备；导出格式由 JVM 回归检查，未逐一覆盖所有 Android 文档提供商。
- 测试结束清理独立应用与设备临时轨迹；本地截图、报告和轨迹保留，用户 QA 及其数据不变。
