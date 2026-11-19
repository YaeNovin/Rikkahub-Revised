# 搜索引用与 Markdown 渲染修复

日期：2026-11-19。开发中，未发布。

## 问题与处理

模型可能把多个搜索结果写成 `[citation:fae5f3,9d7feb]`。原生与 HTML 渲染路径原先只识别 `[citation,domain](id)`，并限制来源 ID 必须是六个字符，因此这类回复会显示原始标记，部分不匹配的引用还会丢失。

新增共享的 `MarkdownCitations.kt`，仅在显示阶段把完整的紧凑引用转换成可独立点击的引用角标。同一内容中的来源按首次出现顺序编号，重复来源保持编号；保留原有域名引用格式，支持多个来源、空白和全角逗号。ID 接受 1–128 位字母、数字、下划线和连字符，来源回调传递完整原始 ID。

转换使用 Markdown AST 识别正文，跳过代码围栏、缩进代码、行内代码、已有链接、图片、数学与 HTML 字面量。转义、无效及流式生成中尚未闭合的标记保留原文。消息存储和发送给模型的内容不受显示转换影响。

两种渲染路径共用引用角标，并复用现有外观与背景可读性计算。表格单元格补齐引用点击回调；相邻角标按实际占位区域响应触摸，避免扩展触摸区域重叠而点中错误来源。搜索工具提示词同时明确要求多个来源使用独立引用链接。

本轮还修复了以下相关问题：

- 链接标题中的加粗、斜体、行内代码被当成 Markdown 字面量。
- 原生渲染中的标点转义和 HTML 实体未正确解码，以及硬换行丢失。
- 行内代码的定界符处理和连续空格被折叠；代码实体按字面量保留。
- 原生路径未解析引用式链接，改为交给已有文档级链接解析器。
- 尖括号包裹的链接目标被底层解析器表示成 `AUTOLINK`，导致 URL 为空；HTML 路径修正保持限定在 HTTP、HTTPS 和 mailto 协议。

HTML 源码保护策略继续生效，例如原始 `<br>` 可以按现有策略显示为源码；本轮不扩大 HTML 执行范围。

## 自动化验证

最终构建日志：`build/reports/qa-build/20261119-103941/attempt-1.log`，结果为 `BUILD SUCCESSFUL in 6m 9s`。

```text
python scripts/build_qa.py :app:assembleDebug :app:testDebugUnitTest --tests '*MarkdownCitationTest' --tests '*ChatInlineDecorationsTest' --tests '*RawXmlRegressionTest' --tests '*LinkActionsTest' --tests '*StreamingHtmlReuseTest' --tests '*HtmlRenderPolicyTest' --performance-probe --offline --max-attempts 1
```

| 测试类 | 数量 | 失败 / 错误 |
| --- | ---: | ---: |
| MarkdownCitationTest | 12 | 0 / 0 |
| ChatInlineDecorationsTest | 12 | 0 / 0 |
| RawXmlRegressionTest | 5 | 0 / 0 |
| LinkActionsTest | 2 | 0 / 0 |
| StreamingHtmlReuseTest | 2 | 0 / 0 |
| HtmlRenderPolicyTest | 8 | 0 / 0 |
| 合计 | 41 | 0 / 0 |

XML 结果位于 `app/build/test-results/testDebugUnitTest/`。新增用例覆盖紧凑引用、重复 ID、字面量保护、流式不完整内容、ID 长度、无效回退、相同来源不同标签、链接标题格式、实体解码、代码空格、尖括号 URL 和引用式链接。Python 测试脚本通过语法检查，相关已跟踪源码通过 `git diff --check`。

## USB 真机验证

设备为 vivo V2301A，Android 16 / API 36。安装独立 Debug 包 `me.rerere.rikkahub.revised.renderperf`，版本 `2.4.8-revised.9-20261119-104040-debug`。本轮没有覆盖 QA 主包或修改其对话和设置。

APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。

SHA-256：`8b33f704a678a2c85e2675081f8d1fddf370ac8c3f8588978879757797fda084`。

`CitationRegressionActivity` 使用随机上下文与合成搜索来源，直接运行生产环境的 `MarkdownBlock` 和 `MarkdownNew`。触摸位置来自 UI 树，逐项断言点击后的精确来源 ID，而非只检查角标是否出现。

| 路径 | 主题 | 四个来源点击（含表格） | 长按来源 | 流式补全 | 代码原文 | 连续滑动后进程 |
| --- | --- | --- | --- | --- | --- | --- |
| 原生 | 深色 | 通过 | 通过 | 通过 | 保留 | 稳定 |
| 原生 | 浅色 | 通过 | 通过 | 通过 | 保留 | 稳定 |
| HTML | 浅色 | 通过 | 通过 | 通过 | 保留 | 稳定 |
| HTML | 深色 | 通过 | 通过 | 通过 | 保留 | 稳定 |

复现：

```text
python scripts/qa_markdown_citations.py --serial SERIAL --output build/reports/markdown-citation-device
```

脚本要求先安装上述独立 Debug 包，并检查已安装版本与本地构建元数据一致。每组完成多次快速交替滑动和单向滑动，验证进程未重启。测试后的系统退出记录没有新增崩溃或 ANR；设备返回的进程 logcat 与所查时间段的 crash buffer 均为空，不能把空日志单独视为无异常的证明。原始充电亮屏设置为 `0`，已恢复并读回确认。

本地证据位于 `build/reports/markdown-citation-20261119/device/`：

- `device-results.json`：四组断言和精确来源回调。
- `final-audit.json`：最终测试统计、APK 哈希、设备版本、系统退出记录与亮屏设置。
- `native-dark.png`、`native-light.png`、`html-light.png`、`html-dark.png`：渲染截图；另有流式完成及代码截图。
- 各阶段 UI XML 和 `device-logcat.txt`。

本轮验证覆盖实际设备上的生产渲染组件与合成来源回调，没有发起真实供应商搜索请求，也没有进行帧率基准测试；不能据此推断所有供应商、全部 Markdown 语法或其他设备已验证。
