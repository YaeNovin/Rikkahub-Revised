# Gemini 助手内置工具与豆包搜索 Key

核对日期：2026-11-12。文档通过 AnySearch 搜索并提取官方正文。

## 豆包搜索的密钥规则

依据：[Custom 接口](https://www.volcengine.com/docs/87772/2272953?lang=zh)、[官方计费说明](https://www.volcengine.com/docs/87772/2272951?lang=zh)、[版本差异](https://www.volcengine.com/docs/87772/2272949?lang=zh)。

官方明确规定“订阅套餐与按量计费模式的 API Key 相互隔离”，并说明订阅套餐仅支持 Custom 的 web / image，不支持 Global。Custom / Global 的按量使用分别计费，并不等同于要求每个版本必须生成一把专用 Key。共用后付费 Key 时仍需确保相应服务权限已开通；不能把 Custom 订阅 Key 当作 Global Key 使用。

本轮为两个版本分别保存 Key，切换版本不会覆盖另一版本。旧配置的 `apiKey` 仅在相应版本尚未编辑时提供兼容值；明确清空某版本 Key 后不会从旧字段恢复它。配置页分别提示可用的计费方式，10409 错误提示检查 Key 类型，不会自动重试另一计费模式或接口。

Global 官方页本次通过 AnySearch 仅提取到导航壳，因此密钥结论以成功读取的官方 Custom 和计费正文为依据，没有将第三方教程作为鉴权契约。

## 配置入口与优先级

- 助手设置增加“Gemini 内置工具”，集中设置 Google 搜索、搜索类型、时间范围和 URL 上下文。
- 原 Gemini 参数页只保留生成参数。供应商模型页隐藏重复的 Google 搜索与 URL 上下文开关，并提示新入口；图像输出开关保留。
- 聊天搜索按钮使用当前助手的同一份配置，不再修改所有助手共享的 Gemini 模型开关。
- 历史模型开关作为尚未编辑的助手初始值兼容读取，首次编辑将有效值保存在助手中。已有助手之间不相互覆盖。
- 页面按模型名称／ID 的 Gemini 家族判断；不因供应商是 Google 就把 Gemma 识别为 Gemini。切换为 GPT、Gemma 等模型后，入口隐藏；已打开的 Gemini 工具页立即停止显示内容并返回助手设置。模型切回后保留助手偏好。
- 这些工具使用 Gemini 原生协议；兼容 OpenAI 通道不发送原生工具字段，保留其原有搜索路径。

## 官方能力核对

参考：[搜索说明](https://aistudio.google.com/docs/google-search)、[GoogleSearch / UrlContext API 类型](https://ai.google.dev/api/caching#GoogleSearch)、[URL 上下文](https://aistudio.google.com/docs/url-context)、[工具组合](https://aistudio.google.com/docs/tool-combination)。

| 官方能力 | 处理 |
| --- | --- |
| GoogleSearch.searchTypes | 网页、图片搜索类型；图片增强按官方已确认图像模型开放 |
| GoogleSearch.timeRangeFilter | 成对 startTime / endTime，RFC 3339，开始含、结束不含，校验时间顺序 |
| UrlContext | 官方空对象工具；完整网址通过用户消息传递，无额外 URL 列表字段 |
| URL 限制 | 页面说明每次最多 20 个 URL，每个内容最大 34 MB、公开可访问、不会递归读取链接 |
| 来源与搜索建议 | 保留网页／图片来源、Google 搜索入口 HTML、查询词和 URL 读取结果 |
| Grounding supports | 补存原文片段、partIndex、字节起止位置及其来源关联，引用展开区显示已引用的原文 |
| 流式工具回传 | 延用服务器工具调用／响应及思考签名的回传处理，不迁移请求协议 |

“内置搜索”在此指 Google Search、Image Search grounding 与 URL Context；Maps、File Search 等独立产品不视为漏失的搜索参数。Google Image Search 是图像模型的网页图片参考能力，不是 Google Lens 专用反向检索 API。文本模型不会收到不支持的 imageSearch：从图像模型切换时保留偏好，当前请求回退为网页搜索。

## Gemini 4

官方 [Argon 模型页](https://aistudio.google.com/docs/models/gemini-4-argon)、[Carbon 模型页](https://aistudio.google.com/docs/models/gemini-4-carbon)均明确支持 Google 搜索、URL 上下文、函数调用；[工具组合说明](https://aistudio.google.com/docs/tool-combination)明确适用于 Gemini 4 和 3。

原代码仅将 Argon 纳入新版工具分支。现在以 Gemini 代际判断混合工具支持，Carbon 同样保留函数与内置工具组合及 VALIDATED 模式。依据 [Carbon 更新说明](https://aistudio.google.com/docs/whats-new-gemini-4-carbon)，Carbon 复用 Gemini 4 的 low / medium / high 思考、默认 medium、统一上下文预算、长输出续传、较长请求超时及采样／显式缓存清理。原 Argon 行为继续保留。

## 验证

JVM 覆盖双版本 Key 保存和旧配置兼容、单助手搜索状态隔离、非 Gemini 页面条件、Gemini 4 请求组合与参数、引用片段解析，以及既有搜索／AI／应用回归。

USB 真机使用隔离 Debug 应用，运行正式助手页面、导航和模型参数组件；Python 入口 `scripts/qa_gemini_tools.py`，验证模型切换隐藏页面、旧开关迁移、供应商模型页去重和两把测试 Key 切换保存。不执行真实供应商付费调用。

最终结果：

- `:app:testDebugUnitTest` 1187 项、`:ai:testDebugUnitTest` 481 项、`:search:testDebugUnitTest` 37 项，合计 1705 项全部通过，无失败、错误或跳过。
- Debug 和 QA 构建成功，构建日志：`build/reports/qa-build/20261112-033119/attempt-1.log`。`git diff --check` 通过。
- USB 真机 vivo V2301A / Android 16 的六项 UI 回归全部通过：旧开关初始值与助手保存、Carbon 切换保留状态、GPT 切换退出并隐藏、Gemma 隔离、供应商模型页去重、Custom / Global 测试 Key 分别保存。结果与截图：`build/reports/gemini-tools/device/`。
- ROM 本次未向 ADB 返回任何 logcat 内容，因此不能把空错误日志当作完整崩溃审计。启动等待改为核对持久化的启动次数已增加；六项操作中进程 PID 保持一致，页面状态与实际保存值逐项核验。
- 已使用 `adb install -r` 覆盖安装 QA `2.4.8-revised.9-20261112-033130`，正式包 `me.rerere.rikkahub` 正常启动并显示聊天输入框；首次安装时间仍为 `2026-10-17 22:49:10`，未卸载或清理数据。安装核验记录：`build/reports/gemini-tools/device/qa-installation.json`。
- 真机自动锁屏时长已恢复原值 600000 毫秒，隔离 Debug 已停止。真实供应商鉴权、计费权限及在线生成未进行付费调用验证。
