# 正文注音、古文竖排与校勘注

2026-11-28。Android 用户消息、模型回复和整条消息的网页预览使用同一组受控正文语义；渲染目录版本为 10。原有语言学 JSON 围栏与工具入口继续保留。

## 无需代码围栏的正文

下面的内容直接写入 Markdown 正文。存储的消息、复制出的原始消息和发送给模型的文本不因显示而改写。

| 内容 | 写法 | 显示 |
| --- | --- | --- |
| 拼音 / 假名 | `<ruby>汉字<rt>hàn zì</rt></ruby>`、`<ruby>漢字<rt>かんじ</rt></ruby>` | 读音附在原文上方，词组整体保留 |
| 注音符号 | `<ruby lang="zh-Bopo" data-position="inter-character">漢<rt>ㄏㄢˋ</rt></ruby>` | 原文右侧的竖向注音；建议每个汉字独立一组 |
| IPA | `word /t͡ʃ/`、`<ipa>kat</ipa>` | 随包 Doulos SIL 字体，统一显示 `/…/` |
| 句法树 | `<syntax-tree>{"version":1,"root":{"label":"S","children":[{"label":"NP"},{"label":"VP"}]}}</syntax-tree>` | 正文中的树图，沿用既有节点、深度与标签上限 |
| 日文竖排 | `<vertical lang="ja">古文与注音……</vertical>` | 由右向左排栏、从上向下阅读，宽内容横向滑动 |

也接受 `<div class="vertical-rl" lang="ja">…</div>` 和限定的 `writing-mode: vertical-rl` 声明；`vertical-lr` 必须显式声明。作者的其他 CSS 与属性不会带入显示页。Ruby 可用 `data-position="under"` 在另一侧注音。多层 Ruby、任意样式和自动读音推断不在本轮范围内。

普通 ASCII 路径 `/tmp/`、网址、数学中的除号不会自动解释成 IPA。自动识别斜杠写法需要包含 IPA 专用字符、组合附加符号或重音等特征；纯 ASCII 音标用 `<ipa>` 明确指定。此前 IPA JSON 的 `notation` 字段仍可读取，但当前显示统一用斜杠，不把显示分隔符当作音系学分析结论。

语义标签必须完整闭合。流式输出尚未闭合时保留源码，不猜测缺失内容；代码围栏、行内代码、缩进代码和链接目标不应用这些转换。裸 JSON、任意方括号树和普通词语不自动生成句法分析。

## 古日语与文献学

原样保留历史假名、踊字、组合浊点、异体字符和变体选择符，包括 `ゐゑヰヱ`、`々ゝゞ`、`〱〲`。不进行 NFKC、现代假名转换或音读/训读推断。竖排使用 CSS Writing Modes、严格换行规则及字体的 `vert` / `vrt2` 特性处理标点与小假名，Ruby 随排版方向位于字旁。短数字串可用 `<tcy>12</tcy>` 作纵中横。

日文、汉字及罕见古字仍依赖设备字体的覆盖；本轮没有引入一套完整的变体假名字库，也不声称能重现手稿字形、全部训点或完整日文禁则。缺字不能通过自动改写原文来掩盖。

支持以下 **TEI P5 启发的受控片段子集**，不是完整 TEI 文件导入或校勘数据库：

```xml
本文<app><lem>春</lem><rdg wit="#A #B">花</rdg><note>底本作春。</note></app>。
<choice><sic>旧</sic><corr>舊</corr></choice>
<note n="一">本段的校注。</note>
<vertical lang="ja"><ruby>物語<rt>ものがたり</rt></ruby>。<unclear>疑</unclear><supplied>補</supplied><gap extent="二字"/><lb/>次行。</vertical>
```

- `app` 的 `lem` 作为正文读法，点击 `〔校〕` 展开异文、`rdg wit` 的见证本标记及注释。
- `choice` 优先显示 `corr`、`reg` 或 `expan`，展开后保留 `sic` / `orig` / `abbr` 等原读法。
- `note n` 显示可展开的校注。`lb` 为换行，`supplied` 用括号标记补字，`unclear` 加虚线下划线，`gap extent` 显示缺文说明。
- 标签、文本和见证本标记都是数据，不会读取远程文献、执行脚本或自动产生链接请求。

## 公式与化学式

支持正文的 `$…$`、`$$…$$`、`\(…\)`、`\[…\]`，以及明确的 `\ce{…}` / `\pu{…}`。在 Markdown 处理前保护完整公式，避免反斜杠、下标、紧邻中文、对齐环境和 HTML 混排被解释成普通 Markdown；不重复包裹已有定界符。

正文公式使用现有随包 KaTeX 与 mhchem，同一实例处理化学反应、离子电荷、上下标和单位。保留原生公式工具路径；其中化学式改走相同的离线排版。金额 `$5 … $10` 不作公式。未知命令或无效公式显示明确的失败说明和原式，不静默删除内容。关闭「LaTeX 渲染」时仍可读到公式源码。

## 设置、模型与运行边界

「设置 → 常规」中的 IPA、Ruby、句法树开关同时控制正文与既有围栏。新增「文学竖排 / 校勘注」开关；关闭后显示原文标签和文字。模型的当前请求提示、`list_renderers` 和 `get_session_capabilities` 会获知开关；`annotated_text` / `literature` / `critical_apparatus` 工具入口可提交相同片段，关闭时返回 `renderer_disabled`。

专用段落使用透明的离线 WebView，正文颜色取当前文字样式，字号随 Compose 字体缩放；IPA、公式使用各自的专用字体。只重建许可的 DOM 元素，丢弃作者样式、事件及外部资源，KaTeX 使用 `trust:false` 与展开预算。正文链接和引用只有实际点击才交给原生处理器，脚本触发的导航被阻止。沿用最多 6 个活跃内嵌 WebView 的预算、可见区域加载、父列表垂直滑动与离开后回收。

校勘展开通过 `ResizeObserver` 更新原生高度，不进行后台定时轮询。消息图片导出展开校勘注，长竖排按栏宽分为纵向排列的图片面板；过长内容沿用导出上限与提示。独立 Web UI 尚未同步本轮新增标签。

## 验证

本机入口：`AcademicProseTest`、`LatexNormalizationTest`、`LinguisticRenderSpecTest`、`RenderingToolsTest`，并回归 Markdown、HTML 保护、WebView 资产与导出。`node scripts/verify-academic-prose.cjs` 读取生产 Kotlin 生成的 HTML，使用本地真实 KaTeX/mhchem、字体和句法树脚本，测试 320/412 宽度、深浅色、校勘展开、长竖排横滑与分栏导出、关闭开关、恶意属性和整条消息网页预览；所有远程资源请求均被测试拦截。

真机用例为 `AcademicProseDeviceTest`，在独立 `.revised.citationqa` Debug 包中通过生产 `MarkdownBlock` 检查用户/模型两条路径、深浅色、大字体、化学式、竖排、校勘高度、关闭设置和真实链接手势。不修改真实会话或调用付费模型。

最终结果：

| 检查 | 结果 |
| --- | --- |
| Kotlin / APK | 主源码、androidTest 编译及独立 Debug 主包/测试包构建成功 |
| JVM | 112 项定向回归通过，包括代码/URL 排除、公式边界、金额保护、CJK UTF-8 编码预算、设置回退和模型工具发现 |
| 浏览器 | 新正文 34 场景、既有语言学 33 场景通过；新正文场景外网资源请求为 0 |
| USB 真机 | vivo V2301A 最终包新增 3 项正文用例全部通过；11 项联合回归通过 10 项，旧知识库引用用例发生等待超时，单独重跑通过。更早一轮曾 11/11 通过，但该用例仍存在测试时序不稳定，不把重跑记作最终联合零失败 |
| 目视核对 | 深色大字体的注音、IPA 与反应式清晰；竖排完整显示多字符栏，Ruby 随列排布；校勘展开可读 |
| 校验 | `git diff --check`、脚本语法和文档链接检查通过，临时 USB 保持亮屏设置恢复为原值 |

真机截图发现初版 `max-height:65vh` 会依赖内嵌 WebView 初始高度，把竖排挤成短列；最终改为有界字行长度，新增“初始视口仅 80px”浏览器检查及真机原生高度断言。校勘注另发现 DOM 已增高但原生控件未增高，原因是私有桥接类无法被 WebView 正常反射调用；修正类可见性后，新增 DOM/原生高度共同断言，最终截图确认不再裁切。正文引号高亮、HEX 色块、颜色和字重继承也保持可用，字面代码区域不加装饰。

本机证据保存在忽略的 `docs/local/academic-prose-20261128/`，生产 HTML 与浏览器截图位于 `app/build/reports/academic-prose/`，最终高度修复后的真机截图位于 `app/build/reports/academic-prose-device-height-final/`。验证使用独立 Debug，未覆盖正式版或旧 QA。测试未覆盖所有 Android ROM、罕见古字字体及完整 TEI 排版。

```text
python scripts/build_qa.py :app:assembleDebug :app:assembleDebugAndroidTest --init-script scripts/academic-prose-qa.init.gradle --offline
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class me.rerere.rikkahub.AcademicProseDeviceTest me.rerere.rikkahub.revised.citationqa.test/androidx.test.runner.AndroidJUnitRunner
```

## 参考规范

- [W3C CSS Ruby Level 1](https://www.w3.org/TR/css-ruby-1/)：Ruby 基字、注音配对及方向；该级别为工作草案，不据此声称完整兼容。
- [W3C CSS Writing Modes Level 3](https://www.w3.org/TR/css-writing-modes-3/)：竖排、文字方向与纵中横。
- [W3C 日文排版需求 JLREQ](https://www.w3.org/TR/jlreq/)：假名、标点、Ruby 与日文行排。
- [TEI P5 Critical Apparatus](https://www.tei-c.org/release/doc/tei-p5-doc/en/html/TC.html)：读法、见证本和校勘关联语义。
- [KaTeX 支持表](https://katex.org/docs/supported.html)：公式命令与 mhchem。实际能力以随包版本及回归样例为准。
