# 音乐学：附件、分页、和声与 MEI

更新：2026-11-21。范围为 Android 聊天、专用全屏谱面和谱面 PDF。先增强 ABC、简谱和 OSMD，再为 MEI 增加独立 Verovio 入口。常用 QA/正式版数据不用于合成谱面的测试；本轮不发布正式版。

## 使用方式

| 格式 | 输入入口 | 显示与和声 |
| --- | --- | --- |
| ABC | `abc` / `abcjs` 围栏、`.abc` 附件 | abcjs 6.2.2；引号和弦，如 `"G7"GABc`；以完整系统组成页面 |
| 简谱 | `jianpu` 等原有别名、`.jianpu` 附件 | 固定可读字号、八度/时值、歌词；旋律行后用 `H: I | V7` 对齐每个小节；每项最多 24 字符，空项保留该小节位置 |
| MusicXML | `musicxml` / `music-xml`、识别到的 `xml` 围栏，`.xml` / `.musicxml` / `.mxl` 附件 | OSMD 2.1.2；MusicXML 3/4 `score-partwise`、歌词、和弦符号、功能和声、XML 分页提示与 A4 页面 |
| MEI | `mei` / `mei-xml`、识别到的 `xml` 围栏，`.mei` / `.xml` 附件 | Verovio 6.4.1；现代谱、`mensural.white` / `mensural.black` 定量谱、纽姆音符；MEI 命名空间与一个 `mdiv` |

导入按钮接受上述文件。发送前可点击输入栏谱面附件，发送后也可点击消息内文件标签；历史附件继续从保存的原文件打开。XML 根元素决定是否为谱面，普通 XML 在消息中保留外部打开方式，输入栏预览仅针对识别出的谱面。显式声明为谱面但内容无效时显示错误，不将二进制 MXL 当作文本发给模型。

翻页按钮显示页码和总页数；屏宽变化后重新排版并尽量保留当前页。ABC 和简谱按完整系统/重叠标签分组，不横切音符、八度点或同一行和弦。MusicXML 与 MEI 使用各自引擎的页面布局。谱面使用白纸黑字，翻页控件使用应用当前外观的文字与背景色，复杂壁纸不影响音符对比度。

`整谱 PDF` 位于聊天图形操作栏与全屏预览控制栏。它按引擎页面逐页获取 SVG，再通过 AndroidSVG 和 Android PdfDocument 绘为 A4 矢量 PDF；保留首页、中间页与末页，翻到第几页都可以导出全谱。导出不会改变当前页；如果中途发生屏宽重排则终止，避免混合不同版式。原有 PNG 操作继续保存当前预览，源码下载继续保存原始格式。

## 和声的格式与兼容

ABC 使用原生引号和弦。简谱示例：

```jianpu
title: 和声练习
1=C
1 2 3 4 | 5 6 7 1' |
H: I | V7
L: 一 闪 一 闪 亮 晶 晶
```

MusicXML 的 `<harmony>` 保留标准 `root/kind/bass/degree` 机制。功能分析优先使用 MusicXML 4 的 `<numeral>`；`function` 在标准中已弃用，只保留旧文件显示兼容。OSMD 2.1.2 不刻出 numeral，本轮在**显示 DOM** 中将它转为携带相同 `offset/staff` 的标准文字方向；源文件和可复制 XML 保留原始分析。转位显示为 `inv. N`，避免推断未提供的低音或数字低音。显示层保持根音和和声文字的时间位置。尚未接入交互式和声编辑、自动调性/和弦分析或逐音播放。

## MEI 与 Verovio 的实际取舍

采用 **OSMD 继续处理 MusicXML，Verovio 专门处理 MEI**。MEI 定量谱、纽姆和学术编码不经过有损的 MEI → MusicXML 自制转换。当前入口不是完整 MEI 验证器，也不提供手稿影像、校勘版本切换或录音同步。

实测边界：Verovio 6.4.1 能画出本轮定量谱、现代谱与纽姆音符样例，但对标准 `syllable > syl` 的纽姆歌词会报告定位问题并省略文字。遇到该结构，预览明确提示“歌词尚不参与谱面和 PDF 排版”，原文完整保留。可让模型另附歌词转写；不能据此宣称完整支持所有地区的古谱或全部 MEI 5 标签。

建议下一步先建立真实曲谱样例集，包括纽姆歌词、ligature、editorial/critical apparatus、跨系统连线和多乐章，再逐项核对 Verovio 上游问题。确认歌词定位与出版式导出达标后，才考虑扩大到校勘浏览或以 Verovio 替换更多 MusicXML 场景。当前无需替换所有既有引擎。

Verovio 两个未修改的 ES 模块合计 **7,426,564 字节**（约 7.08 MiB 未压缩；本次 Debug APK 中含 NOTICE 的该目录压缩后为 2,727,455 字节，约 2.60 MiB，另有许可证与应用胶水代码），没有包含 Humdrum 大包。仅 MEI 按需启动 WASM Worker，完成、失败、超时、重排取消或退出时终止 Worker，释放其堆。模块固定为 6.4.1，npm 完整性与文件 SHA-256 已核对。LGPL/GPL 文本、来源、替换与重建说明随 APK 保存，见[第三方归属](THIRD_PARTY_NOTICES.md)。

常规设置中的“MEI 古谱 / Verovio”开关可关闭该入口。关闭后围栏回退为源码，附件不启动刻谱，工具发现返回禁用状态，模型提交也会被拒绝；已有消息与文件不会删除。

## 预算和安全边界

- MusicXML / MEI / 简谱最多 524288 个 UTF-16 代码单元；ABC 维持 131072，并在刻谱前限制 8000 音符/休止符、384 个系统。简谱维持 1600 token。
- MusicXML 每个声部最多 512 小节、24 声部；XML 最多 40000 节点、64 层嵌套、16000 个音符/休止符/纽姆事件。小节预算按声部计数，修正原来多声部合计 200 小节过早拒绝的问题。
- 每份谱面最多 64 页；MEI Worker 15 秒超时。PDF 每页 SVG 最多 2000000 字符，整谱最多 16000000 字符，120 秒超时；失败清理临时文件。
- 原附件最多 2 MiB；MXL 最多 64 条目，全部展开数据合计最多 2 MiB，按 `META-INF/container.xml` 的 MusicXML 主文件声明读取。拒绝路径穿越、重复路径、缺失入口和压缩膨胀；不将 ZIP 解压到文件系统。
- 标准外部 MusicXML DTD 声明在解析前移除，内部子集/实体声明拒绝；原生解析器的外部实体解析器返回空内容。拒绝外部图像、引用、脚本和事件，不给 WebView 开放 file/content 读取或联网权限。
- MusicXML `score-timewise`、MEI corpus/多 `mdiv`、外部 facsimile、音频播放与 SVG/HTML 作者脚本不在本轮范围。

模型通过目录版本 **9、27 类**发现新能力。`list_renderers` 返回示例、限制和当前开关；`render_content` 对 XML 进行同一原生校验，接受输入仍返回 `runtime_verified=false`。没有工具调用能力的模型可输出专用围栏。原始附件保留，同时向上下文提供 MXL 解出的 MusicXML，供模型读谱与标注。

## 构建和验证

```text
python scripts/build_qa.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*ScoreDocumentsTest' --tests '*MusicScoreRenderingTest' --tests '*InteractiveCodeBlockTest' --tests '*AdditionalRendererTest' --tests '*DiagramSourceTest' --tests '*RenderingToolsTest' --tests '*SessionCapabilitiesToolTest' --tests '*WebRendererRegressionTest' --tests '*WebViewAssetPolicyTest' --tests '*WebViewPageTest' --tests '*HtmlPreviewDependenciesTest' --tests '*HtmlLoadingRegressionTest' --init-script scripts/music-qa.init.gradle --offline --max-attempts 1
node scripts/verify-music-renderers.cjs
node scripts/verify-additional-renderers.cjs
node scripts/verify-web-renderers.cjs
```

安装 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` 与 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` 后：

```text
python scripts/qa_music_renderers.py --adb <adb-path> --serial <serial> --output app/build/reports/music-device
```

测试包为 `me.rerere.rikkahub.revised.musicqa`。外部 ADB 负责启动/截图，应用内断言使用生产 WebView、XML/MXL、模型附件转换和 PDF 导出代码；PDF 再由 Android PdfRenderer 逐页重开检查墨迹。报告、生成谱面、截图和 PDF 全部保存于忽略的 `app/build/reports/`，不包含真实聊天或付费模型请求。本轮最终构建与 85 项 JVM 定向回归通过；新增 18 组音乐学浏览器场景（15 个正常谱面与 3 个非法输入）、20 组既有渲染器浏览器回归，以及 13 页脚本语法/模拟接口检查通过。

Android 真机使用 vivo V2301A、Android 16、Google WebView 150.0.7871.181，通过 USB ADB 安装独立 Debug 包。两个仪器测试覆盖 5 种谱面样例 × 深浅色（10 组）：ABC 4 页、简谱 3 页、MusicXML 9 页、定量谱和纽姆谱各 1 页，共重新打开并检查 36 个 PDF 页面。另核对 MXL 展开、原附件保留、模型上下文中的 MusicXML 与 Android 原生 MEI 解析。测试完成后恢复原亮屏设置。

目视对照还发现 Verovio 的 `g.placeholder` 编辑占位点虽在 WebView 中隐藏，AndroidSVG 却会把其中椭圆绘进 PDF。本轮在共同 SVG 输出阶段移除隐藏编辑占位/光标节点，保持显示与导出一致；原始 MEI 保留。新浏览器回归断言导出不包含该节点，最终 Android 导出再次验证，深浅两套样例中的占位点区域黑色像素均由 275 降为 0。真实 ChatList 消息链路也已核对 MEI 分页控件与“整谱 PDF”操作入口。

最终本机汇总：`app/build/reports/music-verification.json`；最终真机记录：`app/build/reports/music-device-final/`（含截图、36 页 PDF 重开验证、占位点像素对照与合成附件）。

## 参考

- [OSMD 选项](https://opensheetmusicdisplay.github.io/classdoc/interfaces/IOSMDOptions.html)与[页面 API](https://opensheetmusicdisplay.github.io/classdoc/classes/OpenSheetMusicDisplay.html)
- [MusicXML 4 harmony](https://www.w3.org/2021/06/musicxml40/musicxml-reference/elements/harmony/)
- [MEI 5 纽姆记谱](https://music-encoding.org/guidelines/v5/content/neumes.html)
- [Verovio 编码格式](https://book.verovio.org/interactive-notation/encoding-formats.html)、[定量谱](https://book.verovio.org/advanced-topics/mensural-notation.html)、[Toolkit](https://book.verovio.org/toolkit-reference/toolkit-methods.html)
