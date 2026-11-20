# Markdown 与 GFM 渲染覆盖审阅

2026-11-20，开发基线为 `2.4.8-revised.10`（`d93f8632`）。聊天正文使用 JetBrains Markdown 的 GFM 分支，检测到原始 HTML、图片或引用式链接等节点时改走 HTML/Jsoup，再由 Compose 渲染。整条消息的离线网页预览使用 markdown-it 14.0.0，网页版使用 Streamdown 2.5.0、remark-gfm 4.0.1、CJK 插件与 rehype-raw。相关入口见 [Markdown.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt)、[MarkdownNew.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt)、[mark.html](../app/src/main/assets/html/mark.html) 和 [markdown.tsx](../web-ui/app/components/markdown/markdown.tsx)。

[GFM 0.29-gfm 规范](https://github.github.com/gfm/)列出的扩展为表格、任务列表、删除线、字面自动链接和受限原始 HTML。尖括号自动链接来自 CommonMark。脚注由 GitHub 产品和 remark-gfm 实现，上下标依赖 HTML 标签或公式语法，提及和提示框属于 GitHub 产品能力。参见 [CommonMark 自动链接](https://spec.commonmark.org/0.31.2/#autolinks)、[GitHub 写作语法](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax) 和 [remark-gfm 4.0.1](https://github.com/remarkjs/remark-gfm/tree/4.0.1)。

| 语法 | Android 聊天正文 | 整条消息离线网页预览 | 网页版 |
| --- | --- | --- | --- |
| 删除线 `~~文本~~`、`~文本~` | 两种均识别，纯 Markdown 施加删除线，HTML 分支处理解析器生成的 `span.user-del`。样例中的中文双波浪线正常，三波浪线保持原文 | 双波浪线产生 `<s>`，单波浪线保持原文。样例中的三波浪线产生删除线并留下外侧波浪线 | 单、双波浪线生成 `<del>`，中文样例通过，样例中的三波浪线保持原文 |
| 任务列表 `- [x]`、`- [X]`、`- [ ]` | 解析器识别大小写、嵌套和有序清单。纯 Markdown 只以小写 `[x]` 判断勾选，且仍画普通列表标记。HTML 分支读取 `checked`，支持大写并用任务框替代列表标记。两个分支的状态目前主要通过图形表达 | 大小写、嵌套和有序项正常。复选框可点，点击后局部状态改变，重载恢复消息原值 | 大小写、嵌套和有序项正常，复选框禁用，表示消息里的状态 |
| 上下标 `<sup>`、`<sub>` | 用户模式接受成对标签，进 HTML 分支以缩小字号与基线位移排版。助手模式把原始标签转义成正文。普通正文样例的 `x^2^` 与 `H~2~O` 没有上下标语义 | 原始标签显示为字面文本，公式由 KaTeX 另行处理 | JSX 配置下的 `rehype-raw` 保留 `sup`、`sub` 和 `kbd`。普通正文的简写无上下标语义 |
| 字面自动链接及尖括号链接 | `https://…`、`<https://…>` 有链接路径。纯 Markdown 对 `www.` 使用原始无协议文本作为目标，HTML 分支生成 `https://` 目标。裸邮件及 `<name@example.org>` 样例都未生成邮件链接。保留平衡括号、移除多余右括号的样例通过解析检查 | `www.`、裸域名、裸邮件、尖括号邮件及 FTP 样例均生成链接，`www.` 补 `http://` | `www.` 补 `http://`，支持裸邮件和尖括号邮件，FTP 和无 `www.` 的裸域名样例保留文本 |
| 脚注 `[^n]` 与 `[^n]: …` | 缺少脚注节点、编号及跳转。多词定义样例留作正文，缩进续写可落入代码块。单词定义样例可能生成指向该单词的普通相对链接 | 同样缺少脚注语义，多词定义残留，单词定义可能成为普通相对链接 | 生成上标编号、脚注区和返回链接，支持同一脚注多次引用。前后跳转都带 `target="_blank"`。两条消息使用同一脚注标签时会重复生成锚点 ID，也会重复生成 `footnote-label` |
| 提及和议题编号 | 样例中的 `@user`、`@org/team`、`#42` 和 `owner/repo#42` 保留原文。GitHub 仓库卡使用独立程序处理显式仓库网址 | 这些样例保留原文，邮件仍按独立规则识别 | 这些样例保留原文，邮件仍按独立规则识别 |
| GitHub 提示框 `> [!WARNING]` | HTML 分支识别 NOTE、TIP、IMPORTANT、WARNING、CAUTION。助手模式样例保留引用段落并由 `HtmlBlockquote` 调用提示框处理。用户模式下，带粗体正文的 WARNING 样例被源码保护包成代码，普通 NOTE 样例仍保留引用结构 | 两个样例显示为带原始标记的普通引用 | 两个样例显示为带原始标记的普通引用 |
| 表格与普通引用式链接 | 支持 GFM 表格和列对齐。样例中后置定义的全写和简写引用均由 HTML 分支生成链接 | 支持表格及列对齐，后置定义样例均生成链接 | 表格和对齐通过。普通引用的后置定义与正文被 Streamdown 分到不同块，动画开启及关闭时都留下原文。隔离改用 `mode="static"` 后，两个引用均生成正确链接 |

## 下一批实现与验收

1. **先修 Android 现有语义的路径差异。** 任务列表统一大小写、列表标记与无障碍状态，离线预览按聊天展示的只读语义处理。用有序起始编号、普通父项与任务子项、空任务、无分隔空格的标记验证。两个 Android 正文分支统一生成链接目标，分别测试裸 `www.`、裸邮件、尖括号邮件、URL 尾标点、括号、中文相邻文本及相同内容由表格/HTML 路由时的表现。保留离线预览额外识别裸域名和 FTP 的行为作为独立决策。
2. **修网页版的文档内导航。** 给脚注引用、定义和标题生成消息范围内的唯一 ID，并保持前后跳转在当前文档。校对现有 CSS 使用的 `.footnote-ref` / `.footnote-backref` 与实际生成的 `data-footnote-ref` / `data-footnote-backref`。优先验证生成完成时以整条消息处理后置引用，流式阶段先显示文本，定义到达或生成结束后校正。
3. **补原生脚注和上下标时沿用消息边界。** 脚注按出现顺序编号，用同一条消息内的定义表支持多次引用和返回位置。定义缺失时保留可读原文，明确定义迟到、重复标签、续写及跨消息同名的行为。区分 `[citation:…]` 搜索引用与脚注。上下标如需对齐用户和助手，可单独定义允许的排版节点，用成对标签和内容解析决定基线位移。保留源码保护对合法提示框和真代码样本的对照测试。
4. **把提及作为独立功能评估。** 先确定目标为应用内对象还是外部 GitHub 用户/团队，再确定链接和显示规则。用电子邮件、代码、已有链接和普通 `#42` 防止误判。

当前 [GfmModernExtensionsTest](../app/src/test/java/me/rerere/rikkahub/ui/components/richtext/GfmModernExtensionsTest.kt) 允许有 `Ctrl` 文本或有任意引用块即通过。后续应分别断言上下标跨度、提示框类型和去掉标记后的正文，任务列表断言复选框状态及无障碍语义，链接断言实际目标，脚注断言前后跳转和锚点唯一性。同时覆盖代码围栏、行内代码、转义、用户和助手角色、深浅主题及相邻消息的重复语法。

固定上游的 BetterText v0.1.5 已把 `span.user-del`、HTML 上下标和任务的 `checked` 映射到统一文档模型，可作为同步参考。该版本解析源码里没有对应的脚注和提及节点。参见 [BetterText MarkdownParser.kt](https://github.com/re-ovo/bettertext/blob/v0.1.5/markdown/src/main/java/me/rerere/bettertext/markdown/MarkdownParser.kt) 和 [MarkdownBlocks.kt](https://github.com/re-ovo/bettertext/blob/v0.1.5/markdown/src/main/java/me/rerere/bettertext/markdown/MarkdownBlocks.kt)。

## 验证范围

13 组样例使用已编译的 QA 预处理函数、锁定版本的 GFM 解析器以及 HTML 生成器，分别跑过用户和助手模式。用 Chromium 加载完整离线预览模板及项目自带资源，核验了 DOM、删除线样式与复选框点击后重载的状态。网页版在 Node 中渲染实际 Markdown 组件，替换翻译、状态、工作台和代码块外层依赖，再用 Chromium 读取 HTML 结构。新版候选的 `static` 模式只在探针内试用。实机 Compose 排版、点击命中及无障碍仍待随对应修复验证。

原始样例、探针和结果存于本机忽略目录 `build/reports/gfm-audit-20261120/`，其中 `native-results.txt` 记录 AST 与生成的 HTML，`browser-results.json` 记录网页结构及交互结果。
