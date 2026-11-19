# 剩余模块专项审阅与开发建议（2026-11-16）

本轮补齐尚未有独立报告的模块，并检查 app 中此前只做过概览的数据与工具管理路径。工作成果是审阅报告和隔离验证样本，未实施业务修复；不能将下文的“样本通过”理解为问题已经修好。

## 1. 范围、已有报告与证据等级

| 本轮范围 | 实际检查的重点 |
| --- | --- |
| document | 六个应用自有解析器、调用方对解析结果的处理、MuPDF Java 释放接口与打包二进制信息 |
| highlight | Compose 入口、缓存与工作线程、模式编译器、正则桥接、扫描器、TokenEmitter、金样生成与现有测试 |
| app:baselineprofile | 启动测量、关键路径生成脚本、设备与构建配置 |
| build-logic、CI、scripts | 三个构建插件、AAR 转换、现有流水线、Python 构建/ADB 公共层及代表性性能脚本 |
| trace-cli | 全部六个源文件、现有测试契约与录制/脱敏/完成状态 |
| locale-tui | XML 读写、翻译、无引用项检测、配置、模型与主要界面操作 |
| web-ui | 聊天状态/发送/断线恢复、Markdown/代码渲染、设置与预览边界、构建和全部五项本地浏览器测试 |
| app 的剩余数据与工具路径 | 知识导入/检索、记忆身份与删除保护、备份/恢复、文件/技能管理、MCP 列表与结果转换、导入导出 |

继续排除 desktop/desktopApp。AI、日志、material3/web、common/search、speech/workspace 的专项结论分别保留在以下报告中，本轮不把相同问题重复算作新发现：

- [总体审阅](../docs/module-review-and-roadmap-20261115.md)
- [ai](../docs/ai-module-review-and-roadmap-20261115.md)、[日志](../docs/logging-review-and-roadmap-20261115.md)
- [material3/web](../docs/material3-web-review-and-roadmap-20261116.md)
- [common/search](../docs/common-search-review-and-roadmap-20261116.md)
- [speech/workspace](../docs/speech-workspace-review-and-roadmap-20261116.md)

**已复现**指本地输入实际触发当前代码行为；**静态确认**指已定位实现与调用边界，尚未做真机/真实供应商复现；**优化建议**不代表已经测到线上故障。P1 优先处理数据完整性、资源峰值或明显功能风险；P2 是功能正确性与可维护性；P3 是补充建设。

本轮对 1,312 个既有源码、测试和配置文件做了哈希保护，结束时无变化。这个数字表示保护范围，不是逐行审阅文件数。未对 MuPDF 全部第三方 Java/JNI 实现、每一种高亮语法、所有 app 页面逐行审计；下列证据反映模块级主要路径及测试覆盖。

## 2. document

### D1 · P1：解析失败仍可能被当成正文，需统一返回契约

**已复现，延伸总体报告 F5。** 缺少核心部件的 DOCX、PPTX、EPUB 都返回非空诊断字符串。知识导入当前主要检查非空和长度，会把这些诊断分块，甚至请求嵌入服务。失败文件可能显示为可用文档。

证据：[DocxParser.kt:25](../document/src/main/java/me/rerere/document/DocxParser.kt#L25)、[PptxParser.kt:29](../document/src/main/java/me/rerere/document/PptxParser.kt#L29)、[EpubParser.kt:20](../document/src/main/java/me/rerere/document/EpubParser.kt#L20)、[KnowledgeDocumentImporter.kt:139](../app/src/main/java/me/rerere/rikkahub/data/knowledge/KnowledgeDocumentImporter.kt#L139)。

建议返回统一的 `ParseResult`，包含正文、来源位置、警告、是否截断及明确失败原因。无正文与损坏文件不能返回成功；部分提取应单独标识。聊天附件缓存、知识索引和错误提示同时适配，避免仅过滤几个英文前缀。

### D2 · P1：输入压缩大小和最终字符串长度不能约束解析峰值

**静态确认并有局部复现，延伸总体报告 F6。** PDF 循环没有显式释放 Document、Page、StructuredText；DOCX/PPTX/EPUB 多数先构造完整正文，再由调用方检查上限。XLSX 的 100,000 字符上限针对单条 shared string，数量和总量继续累积；本轮 12 条输入保留了 1,200,000 字符，已超过最终工作簿的百万字符预算。工作表单个 cell 的临时 builder 也没有同等上限，达到输出上限后仍会继续消费剩余 XML。

证据：[PdfParser.kt:7](../document/src/main/java/me/rerere/document/PdfParser.kt#L7)、[Document.destroy:46](../document/src/main/java/com/artifex/mupdf/fitz/Document.java#L46)、[Page.destroy:42](../document/src/main/java/com/artifex/mupdf/fitz/Page.java#L42)、[StructuredText.destroy:41](../document/src/main/java/com/artifex/mupdf/fitz/StructuredText.java#L41)、[XlsxParser.kt:92](../document/src/main/java/me/rerere/document/XlsxParser.kt#L92)。

建议预算下沉到解析器：限制解压读取量、部件数、共享字符串总字符数、单元格长度、页/幻灯片数量与输出量；在页/块边界检查取消。PDF 用 `try/finally` 逐级释放原生对象。对单次无法中断的原生调用，另评估进程隔离与超时；不能把协程取消等同于原生调用已停止。

### D3 · P2：PPTX 顺序与备注必须按关系表读取

**已复现。** 样本明确声明先 slide1、后 slide2，但 ZIP 内先存 slide2，当前输出先出现第二张内容。`substringAfter("slide")` 首先匹配的是目录 `slides`，数值解析落回 0；即使修成取文件名，也不能代表用户在演示文稿中重排后的顺序。备注同样不能用循环序号拼接 notesSlide 文件名。

证据：[PptxParser.kt:23](../document/src/main/java/me/rerere/document/PptxParser.kt#L23)、[PptxParser.kt:41](../document/src/main/java/me/rerere/document/PptxParser.kt#L41)。

建议按 `presentation.xml` 的 `sldIdLst` 和关系文件找实际 slide，再读取各 slide 的 notes 关系。回归覆盖重排、删除后编号空洞、不同 ZIP 存储顺序及备注错位。Microsoft 文档提供了相应 `sldIdLst/r:id` 结构。

### D4 · P2：EPUB 合法相对 URI 会漏读，部分损坏也缺少告警

**已复现。** `href="Text/../chapter.xhtml"` 实际指向存在的章节，但当前直接拼接 ZIP entry 名称，返回“No readable content”。百分号编码、fragment 等 URI 形式也需要规范化处理。XHTML 内部异常被截断/吞掉时，调用方无法区分完整提取与部分提取。

证据：[EpubParser.kt:32](../document/src/main/java/me/rerere/document/EpubParser.kt#L32)、[EpubParser.kt:219](../document/src/main/java/me/rerere/document/EpubParser.kt#L219)。

建议使用包内 URI 解析，规范化后限制在 archive 根内，再查 ZIP entry；仅保留可读正文并报告缺失章节。这里复现的是包内查找失败，没有据此宣称发生了解压越界写入。

### 需要补足与更新

保留已有 DOCX SAX 回退、DOC 旧格式回退和 XLSX 流式解析。补充真实格式夹具，覆盖编号/超链接/表格转义、XLSX 日期与样式、加密/扫描 PDF、EPUB 章节顺序；不支持的格式特征要在结果中可见。

二进制静态检查显示 arm64-v8a 与 x86_64 的 MuPDF ELF LOAD 段对齐均为 16,384 字节；本轮没有发现“仍是 4K 对齐”的证据。仍应补齐二进制来源、确切版本、构建参数、哈希和更新流程，并用 16K page size Android 环境验证实际装载。不能从 Java 文件版权年份推断 JNI 版本或漏洞状态。

## 3. highlight

目前已有整块解析、未知语言/超长源码保留原文、后台高亮、64 条与 512 Ki 字符双重缓存限制，以及 highlight.js 11.11.1 金样。现有 62 项测试结果有效且本轮 Gradle 判定输入未变，复用了结果。未新确认需要替换整套引擎的正确性缺陷。

| 优先级与性质 | 需要改进的内容 | 修改与验收方向 |
| --- | --- | --- |
| H1 · P2，优化建议 | UI 协程被取消后，同步扫描与 Java 正则内部不会因此立刻停止；迭代防死循环不是耗时预算。 | 在扫描/子语言/关键词边界传入取消检查，限制待处理任务；用切换代码块、退出会话与异常输入验证过期任务结束时间。单次正则回溯仍需输入约束。 |
| H2 · P2，静态成本风险 | TokenEmitter 反复拼接同 scope 的不可变 String，可能产生重复复制；`ThreadLocal` 也不等于“只有两个引擎”。`limitedParallelism(2)` 只限制并发，不固定 Default 调度器使用的线程。 | 对同 scope 用 builder/range 合并；评估有界高亮器池。先测 4K/16K/32K 的耗时、分配和峰值，不能将所有聊天卡顿归因于这里。 |
| H3 · P2，补足 | safe fallback 会掩盖语法编译/扫描失败；Windows JVM 金样不能完全代表 Android ICU/正则行为。 | 记录不含源码的语言、长度、耗时、回退原因；增加 Android 语法差异样本。锁定 upstream 版本，升级时做 token 差分。 |
| H4 · P3，契约维护 | ANSI 源码高亮只标记转义/日志级别，终端视觉解释属于另一条预览路径；深浅色可读性也跨 app 主题。 | 保持复制原文与预览解释分离，补充 UTF-16/CRLF、未闭合控制序列、深浅色样本；避免为了饱和度改动而改写复制文本。 |

证据：[Highlighter.kt:47](../highlight/src/main/java/me/rerere/highlight/Highlighter.kt#L47)、[HighlightEngine.kt:103](../highlight/src/main/java/me/rerere/highlight/core/HighlightEngine.kt#L103)、[MultiRegex.kt](../highlight/src/main/java/me/rerere/highlight/core/MultiRegex.kt)、[TokenEmitter.kt:51](../highlight/src/main/java/me/rerere/highlight/core/TokenEmitter.kt#L51)、[金样生成器](../highlight/tools/generate-hljs-fixtures.mjs)。

## 4. app:baselineprofile

### B1 · P1：生成基准不应依赖真实账号与付费响应

**静态确认。** generator 在当前应用里输入 Markdown 并点击发送。即使目标 ID 从被测 APK 读取，也仍会改变被测会话，并可能使用已配置的真实模型。现在的生成流程不能作为无副作用的通用测试。

证据：[BaselineProfileGenerator.kt](../app/baselineprofile/src/main/java/me/rerere/baselineprofile/BaselineProfileGenerator.kt)。

建议增加只在独立 benchmark 构建启用的确定性本地 fixture：预置长消息、固定流式输出、模拟工具审批，清晰标识 fixture 生效状态。基准开始前断言目标包和 fixture，而不是依赖测试人员当前页面或供应商连接。

### B2 · P2：启动完成与日常交互的测量尚未覆盖完整

`StartupBenchmarks` 只有冷启动 `StartupTimingMetric`；等待完整绘制仍是 TODO，源码中也未检索到 `ReportDrawn/reportFullyDrawn` 接入。generator 的英语 content description 与可选分支容易静默跳过步骤，输入等待结果也未强制断言。

建议定义“主题/设置已就绪且首屏可交互”的完成信号，补齐首帧与完整显示指标；增加抽屉进出、设置返回、键盘、长会话滚动和后台恢复测量。每个步骤必须断言实际发生。保留 None/Partial Require 对照，性能结果使用物理设备；模拟器可用于生成/功能检查。测试模块 SDK 36 与主模块 SDK 37 的差异应统一或记录理由，而非视为已证实崩溃原因。

证据：[StartupBenchmarks.kt](../app/baselineprofile/src/main/java/me/rerere/baselineprofile/StartupBenchmarks.kt)、[baselineprofile 配置](../app/baselineprofile/build.gradle.kts)。本轮未运行会发送消息的 generator 或任何真机 benchmark。

## 5. build-logic、CI 与 scripts

| 编号 | 优先级与证据 | 建议 |
| --- | --- | --- |
| C1 | P1，静态确认：WebUI 有 PR 检查，Daily Build 做 R8/打包；当前没有对 Android 核心 JVM 测试设 PR 门禁。 | 增加 document/highlight、ai/common/search/speech/workspace 及 app 关键回归任务；记录平台跳过与已知失败，避免把“能打包”当成“行为通过”。构建逻辑的插件应用/依赖转换增加 TestKit 覆盖。 |
| C2 | P2，静态确认：已有 HugeIcons SHA 校验和 Termux JNI 替换测试，但 wrapper 未配置 distributionSha256Sum，未发现 Gradle 全局依赖校验/锁定文件。 | 建立 wrapper、关键依赖与 vendored native 的版本/校验清单；AAR 转换不要只靠文件名匹配，补充依赖坐标、变体、ABI 与最终 APK 的集成验收。先审查校验基线，再启用严格校验。 |
| C3 | P2，静态确认：Web 构建依赖 shell 中的 pnpm；copy.ts 先清目标再复制，且忽略清理错误。 | 明确 Node/pnpm 工具链输入、构建环境参数与产物 manifest；复制使用 staging 与可恢复替换。否则复制中途失败可能留下不完整产物，清理失败可能保留过期文件。 |
| C4 | P2，补足：Python 构建/ADB 公共层已可复用，但场景脚本仍分散、部分证据路径固定。 | 统一设备序列号、包名、fixture、产物目录与恢复清理约定；新脚本优先 Python。保留性能原始数据和采样边界，杜绝零帧样本报流畅。将剩余 PowerShell 验证入口逐步迁移到同一公共层。 |

证据：[Daily Build](../.github/workflows/daily-build.yml)、[WebUI checks](../.github/workflows/web-ui-checks.yml)、[WorkspaceTermuxPlugin.kt](../build-logic/src/main/kotlin/WorkspaceTermuxPlugin.kt)、[wrapper](../gradle/wrapper/gradle-wrapper.properties)、[web 构建](../web/build.gradle.kts)、[copy.ts](../web-ui/copy.ts)、[build_qa.py](../scripts/build_qa.py)、[android_qa.py](../scripts/android_qa.py)。

验证边界：`:build-logic:test` 的离线 Gradle 路径在 pluginUnderTestMetadata 阶段因缓存缺少 Kotlin 2.2.10 runtime 依赖而失败，测试没有执行；这不是 AAR 转换断言失败。使用已经编译的相同测试类与本地 Gradle/JUnit classpath，直接执行两项转换/确定性测试均通过。它不能替代插件在 Gradle 中的完整集成验证。Python ADB 公共层的五项 host-only 测试通过。

## 6. trace-cli

录制器已有超时、临时文件、成功后 rename、失败清理，且没有自动重发付费 POST。应保留这些边界。

### T1 · P1：分享轨迹前的脱敏覆盖不足

**已复现局部路径。** 按真实 dry-run 的顺序构造请求时，配置中附带的 `X-Access-Token` 不会被脱敏。当前只匹配包含 authorization/api-key 的 header 名，不清理 URL query 或 body。主认证字段在 dry-run 中先被替换为 `<redacted>`，本轮没有发现 apiKeyEnv 对应的主密钥直接因此泄漏；问题在额外配置字段与原样响应。

证据：[providers.ts:80](../trace-cli/src/providers.ts#L80)、[index.ts](../trace-cli/src/index.ts)。

建议明确区分受限本地原始轨迹与可进入仓库的脱敏 fixture，对 header/query/body/content 建立可配置策略；分享前有自动泄漏边界测试。脱敏后的工具参数仍需保持可重放结构，不应简单删除所有数据。

### T2 · P2：EOF 不等于一次有效生成已完成

**已复现。** 没有空行终止的 `data:` 会在 EOF 被派发；没有任何供应商完成标记的流也能生成最终 events.jsonl 并返回成功计数。现有测试明确要求宽容 EOF，说明这是已有契约，不能只修改 parser 后让原有用途失效。

证据：[sse.ts:87](../trace-cli/src/sse.ts#L87)、[recorder.ts:45](../trace-cli/src/recorder.ts#L45)、[现有 SSE 测试](../trace-cli/test/sse.test.ts)。

建议将“录制结束”和“协议完成”分开：元数据记录 complete/incomplete/error、完成原因及协议。严格 SSE 模式遵守 WHATWG EOF 丢弃未完成事件的规则；兼容采集模式可以保留残片，但标记为未完成。回放测试不能拿截断样本证明完整生成通过。

### T3 · P2：补齐流量预算和 fixture 版本

错误响应先 `response.text()` 再 slice，并不能限制读取峰值；SSE 单行、单事件和整段录制也没有字节/数量预算。建议流式限额读取，设置行/事件/总量上限及明确截断状态；补充 schemaVersion、录制工具版本、协议、时间与规范化请求摘要、预期解析结果。不要把真实提示词和响应默认提交到测试资源目录。

本机未发现 Bun 命令；原有 `bun:test` 套件和整个 CLI 没有据此宣称通过。本轮仅通过 Node/tsx 对实际 providers、sse、recorder 执行三项隔离样本，fetch 为本地替身。

## 7. locale-tui

### L1 · P1：保存会损坏 Android 资源结构

**已复现。** `_get_text_content` 只取 elem.text，`Hello <b>world</b> tail` 读成 `Hello `；`write` 从新 resources 根重建所有 string，属性、注释、命名空间、plurals、string-array 均丢失。`action_save_all` 实际使用了该方法。当前主项目英语资源已存在 plurals，因此并非仅假设将来可能遇到。

`update_entry` 又只修改 elem.text，不移除/更新原有子节点，可能保留旧的加粗文字与 tail；非法 XML 则静默返回空字典，被当成缺失资源。

证据：[xml_parser.py:11](../locale-tui/src/services/xml_parser.py#L11)、[xml_parser.py:33](../locale-tui/src/services/xml_parser.py#L33)、[xml_parser.py:40](../locale-tui/src/services/xml_parser.py#L40)、[translation_table.py:338](../locale-tui/src/screens/translation_table.py#L338)、[strings.xml:490](../app/src/main/res/values/strings.xml#L490)。

建议先改为保留 DOM 的定点更新，保留资源类型、属性、xliff 与混合文本；读取错误必须阻止覆盖该文件。写入使用临时文件和原子替换，提交前做资源 round-trip 和 aapt2 验证。无修改保存应保持语义不变，尽量保持原格式；不能用“把所有文本拼起来”替代富文本结构保留。

### L2 · P2：翻译结果缺少结构与占位符校验

**已复现。** 模拟返回 `{"hello":42,"foreign":"extra"}` 时，translate_batch 原样接受；也不检查输入 key 集、Android 格式占位符和 xliff 是否保留。非字符串进入后续 `.lower()` 或 XML 写入路径可能失败。批次异常被跳过，最终 UI 仍显示总计成功数而没有逐批失败说明。

证据：[translator.py:80](../locale-tui/src/services/translator.py#L80)、[translator.py:136](../locale-tui/src/services/translator.py#L136)。

建议返回值严格验证为当前批次 key 到字符串的映射，检查占位符、转义、XML 片段；按项保留错误与重试，不覆盖原翻译。加入超时、取消、客户端关闭和真实 API 测试显式开关。现有真实翻译测试不适合作为默认离线回归。

### L3 · P2：配置与无引用检测需要跟随项目结构

`Config` 提供 is_source，但翻译和 TranslationEntry 仍硬编码 values；config.yml 还列出未在当前 Gradle 清单中的 rag。DeadEntryFinder 主要匹配静态 R.string/@string，不能证明动态资源名与跨模块调用确实无引用。加载/递归扫描也在界面同步路径执行。

建议从模块与资源目录发现可用配置，贯彻唯一 source language；无引用项标为“候选”并显示依据，补充动态引用/跨模块保留清单，不能依靠该扫描结果自动批量删除。后台执行扫描和保存，缓存源文件索引并排除 build/依赖目录。补齐 plural/array 编辑、translatable=false 尊重规则和离线测试。

证据：[config.py](../locale-tui/src/config.py)、[entry.py](../locale-tui/src/models/entry.py)、[config.yml](../locale-tui/config.yml)、[dead_entry_finder.py](../locale-tui/src/services/dead_entry_finder.py)。本轮五项 Python 隔离样本通过；其中翻译使用 SDK 替身，没有联网。

## 8. web-ui

本轮从当前源码生成了 `web-ui/build/client`，没有运行 copy.ts 覆盖 Android 内置资源。类型检查、18 项 Node 测试、五项模拟后端浏览器测试通过；Lint 有七条 unused 警告。浏览器验证使用本机 Edge，不代表 Android WebView 或真机滚动帧率。

### W1 · P2：Markdown 预处理会改写代码原文

**已复现。** 先记录原字符串中的代码范围，再替换行内公式；字符串变短后，块公式替换仍使用旧范围。在十个行内公式之后，代码围栏内的 `\[literal\]` 被错误改成 `$$literal$$`。tilde 围栏与尚未闭合的流式围栏也未被范围正则保护。

证据：[markdown.tsx:26](../web-ui/app/components/markdown/markdown.tsx#L26)。

建议把公式处理放到 Markdown AST/math 节点，明确跳过 code/inlineCode；至少以同一次扫描和原始坐标处理替换，并支持流式未闭合围栏。验收包括展示与复制源码一致、反引号/tilde 围栏和不同换行。三个隔离样本执行的是生产预处理函数原文，未复制另写一套算法。

### W2 · P2：主页首次发送失败会切走原草稿

**静态确认。** 主页发送先 `setHomeDraftId(createHomeDraftId())`，然后才 await POST。请求失败时旧草稿虽然仍在 store，却不再是当前可见草稿，用户会看到空输入区；当前流程没有相应恢复。

证据：[conversations.tsx:511](../web-ui/app/routes/conversations.tsx#L511)、[chat-input-slice.ts](../web-ui/app/stores/slices/chat-input-slice.ts)。

建议成功确认后再切换/清空草稿，或保留提交快照并在失败时恢复；加请求身份避免未知结果重试导致重复发送。补充主页创建失败、超时、重复点击、请求成功但连接中断的 UI 测试。

### W3 · P2：大对话渲染和高亮仍需要资源预算

**静态成本风险。** 时间线对所有 selectedNodeMessages 做 map，代码块又渲染所有行；sidebar 的无限加载不等于对话 DOM 虚拟化。Shiki 预加载完成后走同步 codeToTokens，发生在调用线程。已有 12,000 字符上限与 200 条 token 缓存应保留，但单条对象规模、语言高亮器数量和总字节成本没有统一预算。

证据：[conversations.tsx:668](../web-ui/app/routes/conversations.tsx#L668)、[code-block.tsx:27](../web-ui/app/components/markdown/code-block.tsx#L27)、[code-block.tsx:155](../web-ui/app/components/markdown/code-block.tsx#L155)、[code-block.tsx:223](../web-ui/app/components/markdown/code-block.tsx#L223)。

建议高亮器实例复用、按需加载与 Worker；缓存增加估算字节/总 token 数预算。时间线及超长块评估可视区域渲染，保持选中/复制、动态高度与滚动锚点。先用 16K+ 输出及重复长消息采集 Long Task、DOM 节点和内存数据，再决定粒度；本轮没有测得新的手机 FPS 结论。

### W4 · P2：断线状态不应把已读内容整页替换为错误页

**静态确认。** 收到有效快照后断线仍会设置 detailError，时间线按此隐藏现有消息，显示错误空状态；重新连上后再重建显示。快照数据虽然保留在 state，阅读会中断，滚动位置也需要专门验证。

证据：[useConversationDetail](../web-ui/app/routes/conversations.tsx#L315)、[ConversationTimeline](../web-ui/app/routes/conversations.tsx#L573)。

建议区分“从未加载成功”和“已加载但离线”，保留当前消息、标示重连并限制需要联网的动作；恢复后保持 message ID/offset 锚点。已有 snapshot、序号防旧数据、取消旧会话和终止错误不重连机制值得保留。

### W5 · P2/P3：前后端能力契约和构建体积继续收敛

前次 material3/web 报告已记录自定义 header 字段和图像传输契约差异，本轮作为依赖项保留，不重复宣称新发现。下一步用共享 DTO schema/fixture 同时验证 Kotlin 与 TypeScript，所有功能页依据实际协议能力显示状态。

当前生产构建通过，出现 sourcemap 定位告警以及大于 500KB 的 chunk 告警；Mermaid chunk 约 1.30MB、WASM chunk 约 622KB（未压缩）。这些体积不等于首屏全量加载时间，应结合路由/网络瀑布确定懒加载边界。CLAUDE.md 内“虚拟滚动”“类型完全对齐”等文字也应随真实实现与契约测试更新。

## 9. app：此前未专项展开的数据与工具管理

### A1 · P1：知识向量身份仍只有 UUID，编辑模型配置后可能混用向量空间

**静态确认。** KnowledgeChunk 保存 model UUID 和 dimension，hasUsableEmbedding 只验证这些元数据及字节/有限值。检索按 UUID 取当前供应商配置生成 query vector。如果用户保留 UUID，却修改 baseUrl、远端 modelId 或维度相同的嵌入参数，旧向量仍可能被视为有效。相同维度不能证明属于同一个向量空间。

证据：[KnowledgeImportCheckpoint.kt](../app/src/main/java/me/rerere/rikkahub/data/knowledge/KnowledgeImportCheckpoint.kt)、[KnowledgeChunkEntity.kt](../app/src/main/java/me/rerere/rikkahub/data/db/entity/KnowledgeChunkEntity.kt)、[KnowledgeRetrievalTransformer.kt:158](../app/src/main/java/me/rerere/rikkahub/data/ai/transforms/KnowledgeRetrievalTransformer.kt#L158)。

记忆模块已经有可借鉴的 [memoryEmbeddingKey](../app/src/main/java/me/rerere/rikkahub/data/model/MemoryIdentity.kt#L19)。建议抽出统一 embedding identity，包括供应商/端点/模型/路由/相关参数/分块版本，知识索引和 query 同时验证。失配先词法回退，再显式重建索引；提供源文件保留与重导入策略。保留当前 128 条分页的精确 top-k，之后再用它作为 FTS/向量索引召回对照，避免硬截断候选。

### A2 · P1：备份/恢复缺少数据库一致性边界

**静态确认。** S3 与 WebDAV 都顺序复制正在使用的 DB/WAL/SHM，没有可见的写入冻结/一致快照协调；恢复直接替换文件并即时发布 Settings，最后才弹重启提醒。文件级 staging/rollback 能处理部分复制失败，不能保证已打开 Room 连接、Worker、聊天任务与恢复中的数据库一致，也不能阻止新 Settings 触发外部工作。

证据：[S3Sync.kt:134](../app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt#L134)、[S3Sync.kt:254](../app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt#L254)、[WebDavSync.kt:161](../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt#L161)、[BackupArchiveSafety.kt](../app/src/main/java/me/rerere/rikkahub/data/sync/BackupArchiveSafety.kt)。

建议建立共同 BackupEngine：先取得一致数据库快照，再归档；恢复先完整校验到 staging，停止生成/导入/索引，关闭数据库连接后替换并整体提交 Settings，最后重建运行时或重启。SQLite backup API/一致快照方案需按现有 Android driver 能力选型，不能直接假定平台已暴露 native backup API。单独执行 WAL checkpoint 也不足以证明后续复制期间没有新写入。

验收应包含并发写入、WAL checkpoint、备份时取消、恢复中断与重启后的 integrity_check/外键/附件引用一致性。本轮没有对用户数据库做破坏性恢复测试。

### A3 · P2：备份临时文件、分页和重复实现需要收敛

两条传输都用秒级 `backup_时间.zip`，并先删除同名缓存；没有共同互斥。并发导出/S3/WebDAV 可碰撞同一临时文件。上传失败清理也不在 finally。S3 列表只取 maxKeys=1000，虽然底层支持 continuationToken，外层没有翻页；WebDAV list 直接 drop(1)，假定父目录永远排第一。

证据：[S3Sync.kt:67](../app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt#L67)、[S3Sync.kt:116](../app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt#L116)、[WebDavSync.kt:143](../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt#L143)、[WebDavClient.kt](../app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt)。

建议任务 UUID 临时目录、受控并发、finally 清理、分页读取，并按规范化 href 识别自身目录。已有范围选择、默认不含 TOKENS、不备份 rootfs、路径过滤、恢复预算和回滚要保留。补齐 archive manifest、格式版本、文件校验、选择性恢复预览；需要加密备份时另设显式用户选项与密钥管理。

### A4 · P2/P3：记忆模块保留现有一致性保护，完善生命周期和检索评估

当前已有模型 fingerprint、内容哈希、revision、批量删除 epoch、条件向量更新、非有限向量拒绝及词法回退。这些路径的相关现有测试通过，不宜推倒重写。

新增关注点是 MemoryExtractionService 的 conversationLocks 只增加、未见移除；长期使用会积累历史会话 Mutex。建议按活跃任务生命周期管理或采用引用计数的 keyed mutex，不能在尚有等待者时直接 remove。增加可观察的后台提取/索引队列、批次失败与重试状态；建立检索数据集，评估多类型记忆、矛盾更新、删除后不复活、来源追溯和长期体积。

证据：[MemoryExtractionService.kt:139](../app/src/main/java/me/rerere/rikkahub/data/memory/MemoryExtractionService.kt#L139)、[MemoryRepository.kt](../app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt)、[MemoryMaintenanceService.kt](../app/src/main/java/me/rerere/rikkahub/data/memory/MemoryMaintenanceService.kt)。本轮未测得可归因于这张小对象表的 OOM。

### A5 · P1/P2：文件删除边界和旧写入路径应统一

**静态确认。** deleteChatFiles 对任意 file URI 调用 file.delete；`getRelativePathInFilesDir` 返回 null 只阻止数据库路径登记，没有阻止删除本身。应先确认文件属于允许清理的应用自有目录并符合符号链接策略，再删除；不应将所有 file URI 都视为可删除附件。

普通 URI/字节/文本保存路径和旧 base64 图像路径，也没有全部复用视频的大小/完整性/partial 清理机制。createChatFilesByContents 在异常后记录日志，但未统一清除已写入文件；trackManagedFile 是独立 AppScope 任务，存在“文件成功、索引失败”的恢复缺口。

证据：[FilesManager.kt:268](../app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt#L268)、[FilesManager.kt:147](../app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt#L147)、[FilesManager.kt:565](../app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt#L565)、[AtomicMediaFileStore.kt](../app/src/main/java/me/rerere/rikkahub/data/files/AtomicMediaFileStore.kt)。

建议统一有界流式写入、尺寸/像素预算、失败清理和可恢复登记；让文件回收依赖引用关系与明确保留策略。当前 fork 已有复制本地附件的逻辑，不能笼统断言“分叉一定共享文件导致删除丢图”。技能导入已有路径校验与 staging，继续补充 runtime 读取大小限制和并发保存/删除测试；SAF 授权失效需要可恢复 UI。

### A6 · P2：MCP 工具分页与结果语义不完整

**静态确认。** syncTools 只调用一次 `listTools().tools`；后续 merge 按这一次结果重建清单，有分页的服务器会漏掉后页工具，并可能清掉它们已保存的选择状态。额外检查了本机实际使用的 Kotlin MCP SDK 0.15.0 字节码：listTools 直接委托单次 request，默认请求也没有 cursor，不会自动汇总所有页。应实现/测试完整 cursor 遍历，限制重复 cursor 与总工具数。

McpManager 将 CallToolResult 只转成 content 的消息列表，isError、structuredContent 等顶层信息没有传递；非文本/图片内容仅序列化成普通文本。结果可能丢失明确错误状态或结构化输出。

证据：[McpSessionRegistry.kt:314](../app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt#L314)、[McpManager.kt:111](../app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt#L111)。MCP 官方工具文档定义了 nextCursor、isError、structuredContent 与列表变更通知。

建议内部执行结果保留错误、结构化内容与各内容类型，再适配聊天 UI/供应商协议；同步时合并分页后再发布，并处理 list_changed。增加本地 MCP fixture 测试分页、schema 变化、结构化/资源/音频结果和取消竞态。保留已有连接 Mutex、配置新旧检查、OAuth 刷新、重连去重与 CancellationException 传播；没有依据对有副作用工具做无条件自动重试。

### A7 · P2：导入导出的限额、失败反馈和格式迁移不统一

通用 ExportSerializer.readUri 全量 readText，ModeInjection 进口没有复用世界书的限额方案；有 version 字段但部分 native importer 没有明确版本分派。ExporterState 写 URI 时没有显式错误/完成回调，openOutputStream 返回 null 也没有报错；rememberImporter 只按 serializer 记忆，可能保留过期 onResult 捕获状态。

第三方导入也需要单独维护格式矩阵：CherryStudio 的 gemini/vertexai 共用 Google 构造分支，目前未在该分支设置 vertexAI 与相关路由。不能把导入了一个 Google 类型对象当成已保留 Vertex 配置。

证据：[ExportSerializer.kt:41](../app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt#L41)、[ExportHooks.kt:65](../app/src/main/java/me/rerere/rikkahub/data/export/ExportHooks.kt#L65)、[ExportHooks.kt:145](../app/src/main/java/me/rerere/rikkahub/data/export/ExportHooks.kt#L145)、[CherryStudioProviderImporter.kt](../app/src/main/java/me/rerere/rikkahub/data/sync/importer/CherryStudioProviderImporter.kt)。

建议统一流量上限、版本迁移、字段丢失提示和 Result 反馈，用 rememberUpdatedState 等方式取得当前回调；导入先显示预览/冲突/不支持字段，再提交。Chatbox 已有流式逐会话导入与稳定 ID 去重，应保留；继续补齐取消、批次进度、局部失败恢复和第三方格式金样。

## 10. 建议实施顺序与完成条件

| 批次 | 范围 | 可验收的完成条件 |
| --- | --- | --- |
| 1：数据完整性 | L1、A2、A5；同时关闭默认运行真实付费 benchmark 的条件（B1） | XML 无损保存；活跃写入下备份可恢复；恢复中断可回滚；文件只清理允许目录；benchmark 明确使用独立 fixture。 |
| 2：解析与向量正确性 | D1/D2/D3/D4、A1 | 损坏文件不会索引为成功；取消/解析预算可观察；PPTX/EPUB 夹具顺序正确；编辑嵌入配置后失效并可重建。 |
| 3：聊天/Web 与工具语义 | W1/W2/W4、A6、L2、T1/T2 | 代码复制原文一致；发送失败草稿可见；断线保留阅读；MCP 多页与错误/结构化结果不丢；轨迹可区分截断与完成并通过脱敏检查。 |
| 4：性能与可持续维护 | H1/H2、W3、B2、C1/C2/C3/C4、A3/A4/A7 | 相同 fixture 的前后性能证据、资源上限、核心 CI 门禁、固定版本/来源清单及格式迁移测试。 |

后续开发可围绕三项共同能力推进：有类型且有预算的解析/文件管线、具备任务身份和恢复语义的后台作业、由统一能力/schema 驱动的 Android/Web 配置。它们能同时改善知识库、备份、MCP 和多端一致性；单纯继续堆模型名称或增加参数页无法解决本轮发现的数据契约问题。

## 11. 本轮验证与限制

| 验证 | 结果 | 边界 |
| --- | --- | --- |
| document JVM | 8/8 通过，其中 4 项为本轮隔离问题刻画 | 使用本地 kxml2 测试依赖验证包解析；MuPDF JNI 未在 Windows JVM 执行。 |
| highlight JVM | 62 项既有结果通过，任务 UP-TO-DATE | 本轮输入哈希未变，复用结果；没有新增 Android 实测。 |
| app 相关 JVM | 24 套件、86/86 通过 | 选择备份、知识、记忆、MCP connection key、技能、文件相关现有测试；不代表分页/数据库恢复/真实供应商已集成验证。 |
| build-logic | 直接 JUnit 2/2 通过 | 完整 Gradle 测试被离线 runtime 依赖缺失阻断，见 C 部分。 |
| Web 类型检查/构建 | typegen、tsc、生产构建通过 | 未执行 copy.ts；构建告警见 W5。 |
| Web Node / browser | 18/18；5/5 | 浏览器使用模拟后端和 Edge。初次 3 项在截图写入被沙箱拒绝后重跑通过；原 docs/local 截图已恢复。 |
| 本轮隔离 Python/Node 样本 | locale 5/5、trace 3/3、前端预处理 3/3 | 断言当前有问题的行为以建立证据；并非修复验收。无真实 API 调用。 |
| Python ADB 公共层 | 5/5 | 全为主机本地输入/输出替身；未发 ADB 命令。 |
| MuPDF 静态二进制检查 | 两个 ABI 的 LOAD alignment 均 16K | 不等同于真机装载、原生堆泄漏或完整 ELF/发行包兼容性验证。 |
| 既有源码保护 | 1,312 文件哈希无变化 | 新报告与验证工具位于 docs 和 build/reports；未提交或发布。 |

浏览器重跑的子进程日志明确记录 3/3 通过；包裹脚本随后在输出 Unicode 勾号时遇到 Windows GBK 编码异常，finally 仍执行了截图恢复。脚本现已显式设为 UTF-8；没有为这个输出问题重复跑已通过的产品测试。

证据目录：remaining-modules-review-20261116（本地证据，未公开）。包含源码哈希、外部文档快照、JVM 结果汇总、原生二进制信息、隔离样本与浏览器重跑日志。Gradle 原始日志在 qa-build（本地证据，未公开）。

## 12. 文档对照

- [Microsoft：Working with presentations](https://learn.microsoft.com/en-us/office/open-xml/presentation/working-with-presentations)：presentation/slide ID 与关系结构，支持 D3。
- [SQLite Online Backup API](https://www.sqlite.org/backup.html)：活跃数据库复制需要一致性与连接/锁协调，支持 A2 的架构方向。
- [Android String Resources](https://developer.android.com/guide/topics/resources/string-resource)：富文本、格式参数、plural 与 array，支持 L1/L2。
- [WHATWG Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation)：空行派发和 EOF 未完成事件处理，支持 T2。
- [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)：关键路径与生成/发行构建差异，支持 B1/B2。
- [MCP Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)：分页、列表变更与完整结果结构，支持 A6。
- [Shiki Best Performance Practices](https://shiki.style/guide/best-performance)：复用高亮器实例与资源成本，支持 W3。
- [Gradle Dependency Verification](https://docs.gradle.org/current/userguide/dependency_verification.html)：校验机制参考，未据此建议直接升级到文档站当前 Gradle 版本。

优先使用 AnySearch 检索与提取；MCP/Gradle 两页提取遇到 503 后使用 Tavily 读取。保存了实际结果与失败记录。MuPDF 搜索没有给出足够精确的 Java 生命周期说明，该部分以本仓库明确的 destroy 接口为证据，没有把搜索摘要当成验证结论。本轮未进行新的上游源码差分；已有总体报告的上游对照继续作为背景，未宣称与上游当前 HEAD 全面对齐。
