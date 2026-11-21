# 语言学与外语渲染

实现日期：2026-11-21。对应[分学科路线](subject-rendering-roadmap-20261120.md)的首批语言学工作，Android 聊天正文接入五类专用渲染；目录版本为 8，传输 schema 为 1。

## 使用与设置

用户消息、模型回复均可使用下面的专用围栏；支持工具调用的模型还可通过 `list_renderers` 查询格式，再调用 `render_content`。工具与围栏共用 Kotlin 校验器，不执行输入中的脚本。普通 IPA 文本或斜线不会被自动改写成音标，避免误识别网址、代码和数学表达式。

在「设置 → 常规」中分别控制 IPA、注音、逐词释义、句法树、依存弧线。关闭后保留原文并显示源码，历史消息与工具产物也遵守此设置。当前开关随请求告知模型；目录输出 `enabled`，关闭的工具调用返回 `renderer_disabled`。目录只承诺接受输入；`runtime_verified:false` 表示尚未取得设备绘制验证，不把参数接受等同于渲染成功。

| 围栏 / 工具 ID | 别名 | 输入与显示 |
| --- | --- | --- |
| `ipa` | `phonetic` | Unicode 音标 JSON；随包 Doulos SIL 7.000，保留组合附加符号、连音符、重音与声调 |
| `ruby_annotation` | `ruby-annotation`、`furigana`、`zhuyin` | 受控 HTML Ruby 语义，用于拼音、假名、注音；`ruby` 继续表示 Ruby 编程语言 |
| `gloss` | `interlinear`、`igt` | 以词为单元对齐原文、词素和释义；同一单元整体折行，支持 `dir:rtl` |
| `syntax_tree` | `syntax-tree`、`constituency` | 有序树 JSON → 固定 SVG 布局；按子节点顺序绘制，宽树横滑，不把字号缩小到适应屏幕 |
| `dependency` | `conllu`、`conll-u`、`ud` | CoNLL-U → 按原词序绘制依存弧线；箭头从中心词指向依存词 |

首版只负责忠实展示提供的数据，不自动转写、生成注音、进行词法/句法分析或合成音频。成分树 JSON 是应用的传输格式，不冒充 Penn Treebank 标准格式。Ruby 首版为横排、上方、单层注音。

## JSON 格式

前四类必填 `version:1`。可选公共字段：`title`、例句 `id`、语言标签 `language`、方向 `dir:ltr|rtl|auto`、纯文本来源 `source`。未知字段、类型错误、超限输入均报错，不静默截断。专用围栏未闭合、仍在流式输出时保持源码。

```ipa
{"version":1,"language":"en","notation":"phonetic","text":"ˈlɪŋ.ɡwɪs.tɪks t͡ʃ ãː"}
```

`notation` 为 `phonemic` 时添加 `/…/`，`phonetic` 添加 `[…]`，`plain` 或省略不添加；输入不要重复外层括号。IPA 不进行 NFKC 或其他会改变转写的规范化。

```ruby_annotation
{"version":1,"language":"ja","segments":[{"base":"日本語","reading":"にほんご"},{"base":"を学ぶ。"}],"translation":"学习日语。"}
```

`segments` 每项为一组原文和读音；不带读音的片段省略 `reading`。不开放任意原始 HTML。

```gloss
{"version":1,"language":"tr","id":"1","words":[{"text":"evler","morphemes":"ev-ler","gloss":"house-PL"},{"text":"güzel","morphemes":"güzel","gloss":"beautiful"}],"translation":"The houses are beautiful.","legend":{"PL":"plural"}}
```

采用 Leipzig 的逐词纵向对应、`-` 词素边界与 `=` 附着词边界规则。`morphemes` 可省略；启用词素层时每个词均须提供这一层，词素行与释义行的边界序列须一致。`3SG.PST` 等点分束可保留。缩略词图例为 `legend`，译文为必填 `translation`。保留输入的大小写，不将整行词汇释义转成小型大写；译文使用独立的自动文字方向，避免 RTL 原文改变英语译文的标点顺序。这只检查结构与排版，不验证分析结论。

```syntax_tree
{"version":1,"root":{"label":"S","children":[{"label":"NP","children":[{"label":"I"}]},{"label":"VP","children":[{"label":"study"},{"label":"linguistics"}]}]}}
```

叶节点省略 `children`。图形附带可展开的结构文本。

## CoNLL-U

`dependency` 输入为原始 CoNLL-U 文本，不包 JSON。十字段以 **TAB** 分隔，空行分句，Unicode 为 NFC。列依次为 ID、FORM、LEMMA、UPOS、XPOS、FEATS、HEAD、DEPREL、DEPS、MISC。

```conllu
# text = I study.
1	I	I	PRON	_	_	2	nsubj	_	_
2	study	study	VERB	_	_	0	root	_	SpaceAfter=No
3	.	.	PUNCT	_	_	2	punct	_	_
```

基本树要求整数词 ID 从 1 连续编号、单根、HEAD 引用有效且无环。多词范围 ID、空节点（含 `0.1`）与增强 DEPS 被保留；增强边以虚线绘制，基本边为实线，ROOT 单独展示。多词范围单列说明，全部十字段及注释可展开查看，不静默丢弃 LEMMA、FEATS、MISC 或增强依存。本实现是有界可视化适配器，不是完整 UD 语料质量检查器。

## 基础契约与限制

- 各格式上限 32768 个 UTF-16 代码单元。IPA 文本至多 4000；Ruby、释义各至多 128 个单元。
- 成分树最多 128 节点、深度 16、每节点 32 个子节点，标签最长 80。
- CoNLL-U 最多 8 句，每句 80 个整数词 / 100 行 / 240 条弧线；超出应拆分。
- 专用本地脚本与字体离线打包，加载前完成格式校验，字体就绪后再测量图形，文字与图形统一跟随系统字体缩放（0.5–3 倍范围）。输入只作为数据和文本，不开放 `eval`、作者脚本或远程资源。
- 短内容预览按实际内容测量高度（88–400dp），颜色重载保留原高度，避免大块留白和二次滚动跳动。复用现有 WebView 的可见区域初始化、后台暂停、销毁、源码切换、复制和全屏链路；全屏语言学页面保留原生选择与滚动，不注入通用图形拖拽脚本。
- 卡片 PNG 按按钮语义保存当前可见预览；整条消息图片导出使用独立导出视图，展开字段与结构文本，宽图缩放以纳入整图，长内容沿用消息分页与超限提示。
- 原生聊天与其专用全屏预览已接入。整条消息的 `mark.html` 网页预览和独立 Web UI 仍按代码围栏回退，不能据 Android 目录推断这些路径也支持五类渲染。

## 验证与复现

定向 JVM 套件：`LinguisticRenderSpecTest`、`LinguisticRendererTest`、`RenderingToolsTest`、`SessionCapabilitiesToolTest`，并回归 WebView 资产策略、全屏处理、HTML 依赖和加载行为。

`node scripts/verify-linguistic-renderers.cjs` 读取 JVM 生成的生产 HTML，检查本地字体、320/360/412 宽度、深浅色、逐词对齐、图形边界、依存箭头、脚本注入防护、源码错误态及消息导出。不会联网加载测试资产。

设备使用 `scripts/linguistic-qa.init.gradle` 构建独立 `.revised.linguisticqa` Debug 包，再安装对应 androidTest 包；`scripts/qa_linguistic_renderers.py --serial SERIAL --output OUTPUT` 执行生产 WebView 与流式/开关回归。样例全部为合成内容，不调用付费模型或改写用户对话。

最终结果：

| 验证 | 结果与范围 |
| --- | --- |
| 离线构建 | Debug 主包、androidTest 包及 Kotlin 编译通过；默认 Gradle native 路径恢复可用 |
| JVM | 43 项通过，0 失败 / 0 错误 |
| 浏览器 | 33 场景通过，涵盖 320/360/412 宽度、深浅色、两倍字体、RTL、长标签、宽树、注入防护与导出高度收敛；词汇大小写与译文方向已检查 |
| 真机 | V2301A、Android 16、Google WebView 150.0.7871.181；2 项仪器测试通过，覆盖五类 × 深浅色的 10 组页面及对应全屏、短卡高度、流式未闭合和关闭开关后的源码 UI |
| 实际聊天链路 | 独立 Debug 的 `RenderStressActivity` 使用生产 `ChatList` 验证注音围栏预览 |
| 校验 | `git diff --check`、脚本语法检查、随包脚本/字体与源码字节一致性通过 |

本地记录：`app/build/reports/linguistic-verification.json`、`linguistic-renderers/` 和 `linguistic-device-complete/`。输出目录均为构建产物，不作为仓库源文件上传。整条消息 PNG 的新格式专项验证覆盖布局与就绪契约；本轮未逐一触发系统保存对话框验证所有导出文件。

复现构建与真机测试（已连接并授权的设备）：

```text
python scripts/build_qa.py :app:assembleDebug :app:assembleDebugAndroidTest --init-script scripts/linguistic-qa.init.gradle --offline
adb -s SERIAL install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python scripts/qa_linguistic_renderers.py --serial SERIAL --output app/build/reports/linguistic-device
```

设备驱动从外部 ADB 启动页面、截图与核对源码 UI，应用内测试只断言生产组件的实际状态，避免该 ROM 的后台启动与应用内 UiAutomation 连接限制干扰结果。测试恢复了临时亮屏设置。

## 来源与许可证

- [Leipzig Glossing Rules](https://www.eva.mpg.de/lingua/resources/glossing-rules.php)：逐词对应与边界。
- [UD CoNLL-U](https://universaldependencies.org/format.html)：十字段、多词范围、空节点和增强依存。
- [W3C CSS Ruby Level 1](https://www.w3.org/TR/css-ruby-1/)：Ruby 的排版语义；所读取版本为 Working Draft，非最终 Recommendation。
- [Doulos SIL 7.000](https://software.sil.org/doulos/download/)：未修改 WOFF2 随包，遵循 SIL Open Font License 1.1。许可证、官方下载地址及 SHA-256 记录在 `app/src/main/assets/html/renderers/fonts/DoulosSIL-*`。
