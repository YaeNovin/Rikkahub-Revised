# 聊天渲染现状与下一步计划

核对日期为 2026-11-20，范围为当前 Android 开发目录、Android 内的整条消息网页预览和嵌入式服务器提供的 Web UI。以代码中可到达的入口为准。表中的“已接入”指存在输入和渲染路径，设备、格式子集与导出结果需按各项回归记录核对。

## 当前目录

Android [RendererCatalog](../app/src/main/java/me/rerere/rikkahub/data/render/RendererCatalog.kt) 为版本 7、21 类入口。具备工具调用能力的模型可用 [list_renderers / render_content](../app/src/main/java/me/rerere/rikkahub/data/ai/tools/RenderingTools.kt) 发现并提交内容。普通 Markdown 和专用围栏可以直接显示。工具返回 accepted 表示请求通过输入检查，实际绘制由客户端完成。

| 分组 | 目录 ID | 当前实现与边界 |
| --- | --- | --- |
| 正文与源码 | markdown、latex、code、diff | Compose Markdown（JetBrains GFM 与 Jsoup）、JLaTeXMath、Kotlin 高亮、原生 Diff。支持表格、图像、引用、任务列表、色块和引号着色等正文能力。脚注在 Android 仍按可读原文显示。 |
| 标记与矢量 | html、svg | HTML 源码默认显示，手动打开静态预览。SVG 使用专用静态图形预览，SVG 图片附件还可以通过图片解码路径显示。 |
| 关系与语法图 | mermaid、graphviz、railroad | Mermaid、Graphviz WASM Worker、铁路图及部分 EBNF。Graphviz 依赖系统 WebView 的 WASM 和模块 Worker，布局超时 15 秒。 |
| 图表、地理与时序 | echarts、vega、leaflet、wavedrom | ECharts、Vega/Vega-Lite、Leaflet/GeoJSON、WaveDrom。Vega 接受内联数据，Leaflet 有离线概览底图和显式在线瓦片入口。 |
| 曲谱与分子 | abc、jianpu、musicxml、smiles | abcjs、项目内的简谱 SVG、OpenSheetMusicDisplay、SmilesDrawer。新版简谱按宽度重排，支持常见升降记号、八度、时值和歌词；大谱使用全屏滚动。MusicXML 接受未压缩的 score-partwise XML，最多 200 个 part/measure 节点。SMILES 支持单个分子。 |
| 数理与纹样 | math_plot、sigil、pattern | JSXGraph 数理绘图、参数化 SVG 法阵和 p5.js Canvas 2D 纹样。三类均由受限声明式 JSON 驱动，并有各自的图形预算。 |
| 终端输出 | terminal | 随包 xterm 只读展示 ANSI 文本。内嵌保留横向滑动，全屏可查看纵向历史。源码复制、下载和当前视口 PNG 都有独立操作。 |

相册图片、GIF、生成图、语音、视频、文档和工具卡属于消息部件。文档模块能提取 PDF、DOCX、PPTX 和 EPUB 的内容；聊天文档按钮当前打开系统选择器，应用内逐页排版应另验收。相关入口见 [ChatMessage](../app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt) 和 [document 模块](../document/)。

## 三条显示路径

| 入口 | 当前覆盖 | 后续缺口 |
| --- | --- | --- |
| Android 聊天正文与代码块预览 | 21 类由正文、专用代码块和模型工具卡承载。通过代码块工具栏切换源码、打开全屏或保存图像。 | 文档级脚注、部分公式语义及导出待补。保存当前视口的 PNG 与完整曲谱、滚动历史是两个交付目标。 |
| Android 整条消息的网页预览 | markdown-it、KaTeX/mhchem、highlight.js、任务列表和离线 Mermaid。 | 曲谱、xterm、Vega 等大部分专用围栏走通用代码块；适合先补统一的可读回退和源码入口，再引入少量高频格式。 |
| Web UI 聊天及代码预览面板 | Streamdown、Shiki、KaTeX 和 GFM。面板识别 HTML/XML、SVG、Markdown、Mermaid，使用独立 iframe，面板内 Mermaid 从外部加载。 | 与 Android 目录缺少共用的能力标记和专用产物映射。优先同步助手正文中已有的产物及其可用状态。 |

入口见 [HighlightCodeBlock](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt)、[整条消息模板](../app/src/main/assets/html/mark.html)、[Web Markdown](../web-ui/app/components/markdown/markdown.tsx) 和 [Web 代码预览语言映射](../web-ui/app/components/workbench/code-preview-language.ts)。

## 开发顺序

| 优先级 | 项目 | 验收 |
| --- | --- | --- |
| P0 | 统一目录与格式校验。先把围栏分派、工具输入校验和错误态的限制对齐，清理文档版本差异。当前工具会查每种格式的 maxChars，互动围栏的通用入口则用 128 Ki UTF-16 代码单元，部分更严格的限制到渲染器才拒绝。 | 用 21 个目录示例验证工具与围栏两条入口。覆盖别名、临界长度、无效数据、未闭合围栏和错误后可查看源码。功能存在、可在当前平台显示、完成绘制三种状态要可区分。 |
| P1 | 对齐已有格式。优先将 Markdown 引用、公式和表格语义同步到整条消息预览及 Web UI。用简谱、只读 ANSI、Mermaid 验证专用产物交接，再逐步覆盖其他格式。Web UI 可先复用已打包的离线 Mermaid 资源。 | 同一条含长代码、曲谱和引用的合成消息分别打开三条路径，核验源码复制、流式结束后的最终显示、浅深主题与超限回退。 |
| P1 | 提升长内容预览与导出。为长曲谱和高图形增加分页或分段导出评估，终端分别保留原始文本下载和可见区域快照。 | 真实 Android WebView 核验旋转、全屏返回、后台恢复和内存占用。图片导出检查首尾页都有内容，超过上限有明确回退。 |
| P2 | 扩展 CSV/TSV 数据预览。先接可横滑、支持复制的原生表格，再把选定列交给已有 ECharts/Vega 渲染。 | 文本与附件用同一解析路径，覆盖引号、分隔符、空值、多行字段、大文件分段；校验数值列和时间列推断。 |
| P2 | 扩展谱面和文档附件。优先让现有 ABC 与 MusicXML 支持附件入口，再评估 MXL 解压和带页码的 PDF 阅读。MusicXML 转简谱可单独比较 OSMD 的可用版本和输入成本。 | 本地 URI 与历史附件可重开。MXL 校验容器和实际解压体积；PDF 按需栅格化页面并保留外部打开入口。对照短谱、长谱、多声部及中文歌词。 |
| P3 | 按实际需求评估专业图形。Mermaid、Graphviz 与 Vega 已覆盖大部分关系图和数据图。若需要交互式 3D，可在本地 Three.js Debug 原型上验证导出和生命周期，再评估接入。 | 用明确的用户任务比较新增收益、离线包体、低内存设备、掉帧、导出和退出资源释放。 |

已有语法问题及待办可继续参阅 [Markdown 与 GFM 渲染审查](markdown-gfm-rendering-audit-20261120.md)、[渲染工具与 HTML 策略](rendering-tools-and-html-policy.md) 和 [图形组件清单](rendering-components.md)。
