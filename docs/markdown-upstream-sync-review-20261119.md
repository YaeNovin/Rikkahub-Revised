# Markdown 与上游同步审阅（2026-11-19）

## 比对基线

- 原上游：[`rikkahub/rikkahub` 的 `ce0e3607a8dc8a450abe5309e12d30f727a6c54a`](https://github.com/rikkahub/rikkahub/tree/ce0e3607a8dc8a450abe5309e12d30f727a6c54a)，默认分支 `master`，最后提交于 2026-11-18。核对时最新正式版为 [3.3.0](https://github.com/rikkahub/rikkahub/releases/tag/3.3.0)。本文针对固定提交源码，不将其全部能力都归入 3.3.0 发行包。
- Revised 已发布基线：[`2.4.8-revised.10` / `d93f863206f96c9df2d282ea8276adb4c7835fd7`](https://github.com/YaeNovin/Rikkahub-Revised/tree/2.4.8-revised.10)。
- 原开发目录：提交仍为 `.9` 的 `7302fc3d`，叠加大量未提交与未跟踪文件，构建版本值已递增为 `.10`。按磁盘实际文件比较，不能只看 `git diff` 的删除列表。
- 标准依据：[CommonMark 0.31.2](https://spec.commonmark.org/0.31.2/)、[GFM 0.29-gfm](https://github.github.com/gfm/)。公式、颜色色块、引号着色和搜索引用属于应用扩展；脚注和 GitHub 提示框也不能仅凭 GFM 名称认定全部支持。

下文保留固定源码、依赖声明和已有测试内容的原始静态审阅；原审阅没有重新构建、运行测试或采集真机性能。“已实现”说明有实现入口，不等于完成全部标准用例和设备验证。

后续 P0 已完成：开发目录现以 `.10` 提交为基线，保留本地独有实验；相关 JVM 回归、固定渲染场景真机验证及 QA 覆盖安装均已完成，见[P0 实施与验证记录](development-baseline-alignment-20261119.md)。P1/P2 仍按下表单独安排。

具体语法的三条显示路径、样例结果及验收建议见 [Markdown 与 GFM 渲染覆盖审阅](markdown-gfm-rendering-audit-20261120.md)。

## 当前架构与上游变化

Revised 原生正文仍走 `Markdown.kt` 的 JetBrains GFM AST 分支，混合 HTML 等内容转入 `MarkdownNew.kt` 的 HTML/Jsoup 分支；公式使用 JLaTeXMath，代码由本地 `highlight` 模块处理。整条消息网页预览和网页版另有独立实现。

上游在 [`24a7422703`](https://github.com/rikkahub/rikkahub/commit/24a742270353bf42d868160570a75872d4b0936f) 迁入 BetterText，当前固定使用 `v0.1.5`：

- `MaterialMarkdown.kt` 负责 Material 主题与默认组件，应用 `Markdown.kt` 注册图片、公式、表格、代码、引用及色块。
- BetterText 的 Markdown 底层仍使用 JetBrains GFM 和 Jsoup，主要新增统一的文档模型、增量处理、排版与选择能力。
- `text`、`markdown`、`latex`、`highlight` 分模块，允许保留应用自己的组件。

主要依据：[上游 Markdown](https://github.com/rikkahub/rikkahub/blob/ce0e3607a8dc8a450abe5309e12d30f727a6c54a/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt)、[MaterialMarkdown](https://github.com/rikkahub/rikkahub/blob/ce0e3607a8dc8a450abe5309e12d30f727a6c54a/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MaterialMarkdown.kt)、[BetterText v0.1.5](https://github.com/re-ovo/bettertext/tree/v0.1.5)。

## 已实现与部分实现

| 范围 | Revised 状态 | 依据及同步判断 |
| --- | --- | --- |
| 标题、强调、删除线、分隔线、引用、列表、任务列表 | 已实现 | `Markdown.kt`、`MarkdownNew.kt`；保留现有行为，补标准语料验证 |
| 链接、自动链接、引用式链接、图片和徽章 | 已实现 | 链接点击/长按、图片预览、SVG 徽章、引用式标签均有专门实现及测试；无需重建基础链路 |
| 原生 Markdown 表格 | 已实现 | `MarkdownTables.kt`、`DataTable.kt`：宽表横滑、列对齐、列宽限制、长文本换行、表头和隔行样式；AST 表格已有复制及 CSV 导出 |
| HTML 表格 | 部分一致 | `MarkdownNew.kt::HtmlTable` 使用相同 DataTable，但没有 AST 分支的复制/CSV 工具栏；宜统一表格卡片入口 |
| 行内/块级公式 | 已实现，能力有差异 | `LatexText.kt`、`MathBlock.kt`、`LatexNormalization.kt`；已有候选回退和 JLatexMathSplitter 长公式拆分，尚未采用上游 KaTeX 原生移植及统一基线布局 |
| 代码围栏、高亮、行号、换行、折叠、复制与导出 | 已实现 | `HighlightCodeBlock.kt`、`highlight/`；完整块高亮、多行注释处理、后台解析及有界缓存继续保留 |
| Diff、ANSI/终端展示 | 已实现 | `DiffView.kt`、`TerminalPreview.kt`，后者为只读展示；与上游普通代码块入口不同 |
| 搜索引用 | 原生增强已实现 | `MarkdownCitations.kt` 识别组合引用、规范引用等，排除代码与链接目标；`MarkdownCitationTest` 覆盖实体转义、代码字面量和引用式链接；迁移时保留其前处理和点击回调 |
| HEX 色块 | 已实现，按既定产品范围 | `ChatInlineAnalysis.kt`、`ChatInlineDecorations.kt`；固定 `#RRGGBB`，尺寸随文字，用户消息与助手正文生效，有开关并保持复制文本。上游还解析更宽的颜色形式，不直接扩展本地约定 |
| 正文引号着色 | 已实现 | 双引号及中文引号、排除单引号和反引号；保持代码原色、按文字取色处理对比度；上游单纯 tertiary 主题色不替代本地壁纸配色 |
| HTML 子集与 details 折叠 | 已实现，状态恢复待补 | HTML 分支支持相应标签；`HtmlDetails` 和 `SimpleHtmlBlock` 的展开状态主要使用 `remember`，页面重建后的恢复弱于上游 `rememberSaveable` 方案 |
| 图表和复杂内容 | 已实现多种应用扩展 | Mermaid、ECharts、Leaflet、SVG、Graphviz、Vega、WaveDrom、SMILES、MusicXML、ABC/简谱、铁路图、数理绘图、法阵、纹样等；具体限额和不支持项见[组件清单](rendering-components.md)，不把 MXL 导入、播放或任意脚本执行记为已有 |
| 流式与历史渲染优化 | 已实现一部分 | 160/320 ms 节流、后台解析、内容预算、缓存、相邻历史预加载、HTML 快照复用及分帧挂载；尚非上游的尾部分块增量解析 |
| 用户消息软换行及粘贴源码保护 | `.10` 已实现，P0 已对齐 | 原审阅时开发目录缺少 `UserMessageSourcePreserver.kt`，角色路由亦有差异；现已恢复 `.10` 的 `USER_MESSAGE`、模式隔离缓存及源码保护，并完成固定场景真机验证 |
| 整条消息离线网页预览 | 已实现独立链路 | `MarkdownWeb.kt` + `mark.html`：markdown-it、KaTeX/mhchem、高亮和 Mermaid；其文字装饰、组合引用与换行策略尚未与原生完全一致 |
| 网页版 Markdown 主体 | 已与当前上游一致 | `.10` 的 `web-ui/app/components/markdown/markdown.tsx` 与固定上游内容一致，已有 Streamdown、CJK、GFM、公式及旧格式引用；无需再次复制整个文件 |
| 网页版代码高亮 | 两种已有方案 | 本地 Shiki/Catppuccin，上游 highlight.js/Atom One；双方都有代码块组件，属于待评估的技术和视觉选择，不记为缺失高亮 |

Revised 发布源码入口：[原生 Markdown](https://github.com/YaeNovin/Rikkahub-Revised/blob/2.4.8-revised.10/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt)、[HTML 分支](https://github.com/YaeNovin/Rikkahub-Revised/blob/2.4.8-revised.10/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt)、[用户源码保护](https://github.com/YaeNovin/Rikkahub-Revised/blob/2.4.8-revised.10/app/src/main/java/me/rerere/rikkahub/ui/components/richtext/UserMessageSourcePreserver.kt)、[网页版](https://github.com/YaeNovin/Rikkahub-Revised/blob/2.4.8-revised.10/web-ui/app/components/markdown/markdown.tsx)。

## 需要同步、改造与验证

| 优先级 | 项目 | 实施范围与验收条件 |
| --- | --- | --- |
| P0（已完成） | 对齐实施用源码基线 | 基线切换至已发布 `.10`，保留 28 份本地独有文件及配置；73 项 JVM 测试及真机 16 项精确文字断言通过，复制入口经代码核验，QA 已覆盖安装。范围与仪器测试超时记录见[实施记录](development-baseline-alignment-20261119.md) |
| P1 | 尾部分块增量解析 | 引入 BetterText 文档/流式适配层，或先移植其增量思路；保留本地预处理、图形入口和预算。复用已完成段落、围栏与表格，测试持续长输出中的解析耗时和分配量 |
| P1 | 统一文字选择及公式行高 | 对照 BetterSelectionContainer、BetterText、LatexInline；核验正文、公式、代码和引用连续选择，长公式换行、混合字号、RTL、复制结果和无障碍。现有 Compose 选择不能直接与 BetterText 的选择容器混套 |
| P1 | 原生 KaTeX / mhchem | 评估 BetterText latex 模块，补齐原生 `\ce`、`\pu`、宏与公式排版；保留显示源码回退、外观取色和导出就绪机制。用实际样例核对原生和网页支持差异 |
| P1 | 工作区图片路径 | 同步上游 `rememberWorkspaceImageUrl` 的路径解析意图，将 `/workspace/...`、Rootfs 文件及含空格/中文的编码路径映射到本地已授权工作区；适配本地仓库层和沙箱边界，再测试本地图片、远程图片及不可访问路径 |
| P1 | details 与表格统一组件 | 对照上游 MaterialMarkdown 组件槽，保存稳定块 ID 下的折叠状态；HTML 和 Markdown 表格共用工具栏、CSV 转义、列对齐、主题及无障碍，保留本地宽表增强 |
| P1 | 三种显示路径的内容一致性 | 把原生组合引用规范化、代码保护、HEX/引号开关的语义扩展到整条网页预览和网页版；当前 Web 主体一致也仍继承旧格式引用约束，不能据此认定已包含 Revised 的新修复 |
| P1 | 标准与迁移回归集 | 建立 CommonMark/GFM 的选定语料与“应用有意偏离”清单，覆盖引用定义后置、未闭合围栏、转义、HTML/Markdown 混排、列表内表格、长链路和两种消息角色；将浏览器/原生截图与选择/复制测试分开 |
| P2 | 助手按段落多气泡 | 上游 `splitAssistantBubbleByParagraph` 与 `MarkdownParagraphs.kt` 已有实现，本地尚无对应设置/分段函数。接入需保护围栏、块公式、缩进列表，以及跨段引用和 details；消息存储仍保持一条逻辑回复 |
| P2 | 高亮器与配色选择 | 核对 BetterText highlight 对本地扩展语言、长代码预算和 WholeBlock 行为的覆盖；Web 的 Shiki 与上游 highlight.js 做 bundle、首次加载、语言范围和深浅主题对比，按结果选择 |
| P2 | 离线公式版本与主题参数 | 本地整条预览 KaTeX 0.16.8、网页版声明 ^0.16.28、BetterText 原生移植 0.19.1，需建立功能矩阵再升级离线包、插件与字体；同时对齐换行和主题参数 |
| P2 | 通用 UI 组件提取 | 上游已拆分通用 UI 和应用注入层；先抽本地主题、图片/工作区、引用、代码/图形和工具栏接口，验证现有高级外观后再决定模块迁移 |

## 迁移时的具体限制

1. **后置引用定义。** BetterText v0.1.5 的 [`MarkdownStreamParser.kt`](https://github.com/re-ovo/bettertext/blob/v0.1.5/markdown/src/main/java/me/rerere/bettertext/markdown/MarkdownStreamParser.kt) 明确注明：后面分块的链接定义不应用到前面已缓存的块。本地已有引用式链接回归，迁移必须补“定义后来才到达”以及生成结束后全量校正的用例。
2. **源码与展示分离。** 未完成的粗体、公式和链接可在展示层补全，但存储、复制、导出及后续发送仍使用原始消息。用户粘贴代码的保护规则与上游默认软换行策略要独立保留。
3. **图形功能覆盖。** 上游当前代码预览只列 HTML/SVG，不能用整文件替换覆盖本地多渲染器识别、Worker 超时、限额、静态 HTML 策略、WebView 生命周期与导出等待。
4. **颜色范围。** 上游正文支持六/八位 HEX、rgb/rgba，行内代码还支持三/四位 HEX。当前用户约定只有 `#RRGGBB`，且代码块不加正文装饰；更宽颜色格式属于后续产品选择。
5. **构建兼容性。** BetterText v0.1.5 文档要求 JDK 25 构建源码；库模块声明 Java 11、minSdk 24、compileSdk 37，Kotlin 2.4.21 / Compose BOM 2026.09.00。Revised 当前本机使用 JDK 21，接入前需验证发布 AAR、Kotlin/Compose 兼容和缓存获取，不能只按源码构建要求推定宿主要求。
6. **原生与 Web 的 HTML 策略。** 离线预览已设置 `html:false`、KaTeX `trust:false`，Web 主体仍使用 `rehypeRaw` 和 KaTeX `trust:true`。需统一允许能力与限制并单独验证；本轮未将这些配置差异直接判为已证实的漏洞。
7. **测试强度。** 当前 `GfmModernExtensionsTest` 允许“仍有普通引用文字”或“有任意 blockquote”即通过，不能证明 `[!WARNING]` 已渲染为专用提示框。脚注、提示框和其他扩展必须有专门结果断言，或明确列为未支持。

## 建议顺序

P0 基线对齐与固定场景已完成；接下来做 BetterText 的解析/选择/公式试接入，保留一层适配器承接现有引用、图表、配色和源码保护。通过长输出、快速滚动和复制回归后，再统一表格/details、工作区图片与三条显示路径。多气泡、额外颜色语法及高亮器更换分别评估，避免和核心渲染迁移绑在同一批。

验收需记录同一设备、同一消息样本下的解析时间、主线程耗时、丢帧、内存峰值和 WebView 数量，并覆盖至少 16K 长文本、公式、表格、代码、图形和连续滚动。本轮比对不能用于宣称迁移后卡顿已解决。
