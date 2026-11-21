# 分学科渲染扩展调研与路线

实施更新（2026-11-21）：[语言学基础](linguistic-rendering.md)与[音乐学首轮增强](music-rendering.md)已接入，音乐学包含 XML/MXL 附件、分页、整谱 PDF、和声和 MEI 专用 Verovio 入口。下文“当前事实”为原调研快照，实际支持范围以实施文档与版本 9 目录（27 类）为准。

日期：2026-11-20；源码基线：`81ba708b1`。本轮为代码核对、AnySearch / Tavily 检索和实施规划，没有新增运行时渲染器或重跑设备测试。下面的新格式名称均为建议，尚未注册为可调用的围栏或工具。

建议先补学术文本和结构化数据的表达，再按实际学科需求增加引擎。现有 21 类入口已经能承担不少图形；“库里存在 API”“应用已开放输入”“当前设备能正确显示和导出”需要分别判断。

2026-11-21 实施增补：首批语言学与外语的共性校验、设置、模型工具发现和五类渲染已接入，格式与验证见[语言学渲染](linguistic-rendering.md)。下文保留 11 月 20 日的调研快照与后续候选排期。

## 1. 已有基础与本轮确认的缺口

核对 [渲染路线](rendering-roadmap-20261120.md)、[组件清单](rendering-components.md)、[HTML 策略](rendering-tools-and-html-policy.md)，并追到当前可达代码：

| 项目 | 当前事实 | 对规划的影响 |
| --- | --- | --- |
| 统一目录 | [RendererCatalog](../app/src/main/java/me/rerere/rikkahub/data/render/RendererCatalog.kt) 为版本 7、21 类入口。部分旧文档仍写版本 6 或“尚未发布”。 | 能力以目录和实际分派代码为准，不能把旧计划里的 WaveDrom、Graphviz、Vega、SMILES、MusicXML 再列作全新接入。 |
| 学术文字 | 现有 Markdown、表格、上下标、公式、搜索引用和源码保护。脚注定义仍在 [MarkdownProseSyntax](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownProseSyntax.kt) 中按可读原文保护。 | 真正的文档级脚注编号、定义区和往返定位仍值得优先补齐；不能把搜索引用角标等同于文献脚注。旧 GFM 审阅中其他问题已有后续修复，不在本轮重新认定为未修复。 |
| Ruby 注音 | 当前排版标签对只开放 `sup/sub/kbd`；[HtmlRenderPolicy](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HtmlRenderPolicy.kt) 的静态标签白名单也没有 `ruby/rt/rp`。 | 仅输出 `<ruby>` 不能获得稳定注音排版，需要专用语义节点或受控注音卡片；不能靠开放任意 HTML 绕过。 |
| 化学 | 随包 SmilesDrawer 2.4.1 已导出 `ReactionParser/ReactionDrawer/parseReaction`，但 [AdditionalRenderers](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/AdditionalRenderers.kt) 只走单分子的 `parse + SvgDrawer`。 | 反应图可先验证已有依赖，未必需要立即新增 RDKit。静态检查发现导出 API，不代表已验证反应绘图、深色显示和导出。 |
| 数理 | 随包 JSXGraph 1.13.3 含 `view3d/vectorfield/slopefield`；[DeclarativeRenderSpec](../app/src/main/java/me/rerere/rikkahub/data/render/DeclarativeRenderSpec.kt) 与目录仍只开放受限二维曲线、点、线段、向量和圆。 | 方向场、向量场与有限三维几何可优先评估扩展现有 schema。单个二维向量已支持；新增价值是场、约束和三维对象，不能重复计数。 |
| 曲谱 | ABC、简谱和未压缩 MusicXML 已有入口；当前 MusicXML 不是 MXL/MEI 附件浏览器。 | 先增强附件、多页和导出，再根据古谱/校勘需求评估 Verovio。 |
| 显示路径 | Android 正文、整条消息网页预览、Web UI 使用不同链路。 | 新格式首先明确在哪条路径可用；完成正文内接入不等于三条路径全覆盖。 |

## 2. 按学科的候选清单

这里的优先级是建议排期，开发成本为相对判断，不是包体、速度或内存实测。

| 学科 | 建议新增或增强 | 可复用的基础 / 候选 | 优先级与主要边界 |
| --- | --- | --- | --- |
| 语言学、外语教学 | IPA 与组合附加符号；原文—词素切分—逐词释义—自由译文的分层对齐；例句编号；句法树与依存弧线 | Compose 专用对齐布局、SVG、Graphviz；Leipzig Glossing Rules、UD CoNLL-U；可评估 Doulos SIL 字体 | **P1**。树形关系可复用 DOT；依存图必须保留词序，不能让通用布局随意重排。后续再支持音频时间片、声谱图与标注。 |
| 历史学、考古学 | 朝代/人物/事件多轨年表；公元前和约略年代；人物关系、迁徙路线；事件与史料互链 | 原生时间线或 ECharts 数值轴、Leaflet、Graphviz；EDTF 时间表示；vis-timeline 为可选组件 | **P1**。需要自己的历史日期语义；现代日期控件或 Mermaid 时间线不能自动处理纪年、不确定区间和不同历法。 |
| 文学、古典学、文献学 | 原文/译文/注释并排；版本异文、校勘注；拼音/假名 Ruby；竖排、行号与段落引用 | Compose 对照卡、受控 CSS Writing Modes / Ruby、TEI P5 子集 | **P1–P2**。先对齐短段落与词组，再评估长篇竖排。现有按行 Diff 可借鉴差异显示，但不等于文献校勘和版本对应。 |
| 艺术史、档案、古地图 | 高清局部放大；图像区域批注；原图与释文对照；多页手稿浏览 | 现有图片入口；OpenSeadragon + IIIF Presentation / Image API 适配层 | **P2**。查看器本地打包不等于馆藏图片离线；Manifest 的目录、分页、版权说明和批注关系需要应用解析。 |
| 数学、统计学 | 隐式曲线、方向场/向量场、积分阴影、几何约束；有限三维曲线/曲面；置信区间、误差棒、箱线图 | 现有 JSXGraph、Vega-Lite、ECharts、LaTeX | **P1–P2**。先扩现有 AST / schema，保持受限表达式；绘图不等于符号求解、统计检验或正确性证明。 |
| 物理、天文学 | 受力图、光路、相图、场线；带明确参数的运动示意；坐标轴、单位和误差范围 | SVG、JSXGraph、Vega；确有需求后再评估受控 Three 场景 | **P2**。运动演示与数值模拟分开定义；投影、时间步长和单位不能由动画外观代替。有限三维几何优先评估现有 JSXGraph。 |
| 化学、材料科学 | 反应物—条件—产物图；多分子面板；立体化学与原子映射显示；MOL/SDF、子结构高亮 | 先验证 SmilesDrawer 反应 API；更广格式和结构操作再评估 RDKit.js；有真实坐标时可评估 3Dmol.js | **P1 反应图 / P2–P3 扩展**。RDKit.js 为 MinimalLib 子集，不等于 Python RDKit 全功能；二维 SMILES 布局不能当作真实三维构象。 |
| 生物学、遗传学 | Newick 系统发育树、枝长和置信度；已比对序列的位点着色、保守位点与缺口；蛋白结构查看 | 简单关系复用 Graphviz；专业树评估 phylotree.js；序列可先做有界原生/Canvas 网格，MSAViewer 作为参考；三维候选 3Dmol.js | **P2**。树形布局不能伪造枝长；MSA 查看器不负责自动完成序列比对。MSAViewer 的旧依赖、维护和打包情况须另核验。 |
| 地理、环境科学 | 时间滑块、迁移轨迹、分层专题图；地形剖面、栅格图例、观测点误差 | 现有 Leaflet/GeoJSON、ECharts、Vega；历史影像可复用 IIIF | **P1–P2**。首先规范经纬顺序、坐标参考系、图例和来源；街道瓦片、离线概览底图、专题数据是不同内容。 |
| 经济学、社会学 | CSV/TSV 表格与图表联动；调查数据、误差带、桑基图、关系网络；缺失值和样本量显示 | 原生表格、ECharts、Vega-Lite、Graphviz | **P1**。优先补文件入口、字段类型、单位和数据出处，无需仅为基础统计图再加入一整套 Plotly。 |
| 计算机、电子工程 | AST、语法树、自动机、算法步骤；模拟电路原理图、元件与连接；寄存器/协议字段图 | Mermaid、Graphviz、Railroad、WaveDrom、只读终端；电路图可评估 circuit-to-svg 的受限适配 | **P1–P2**。时序波形已有；模拟电路连接图是新增价值。Circuit JSON 到 SVG 不等于自动布线、SPICE 仿真或代码执行。 |
| 音乐学 | 乐谱附件、压缩 MXL；长谱分页与整谱导出；逐音符说明、和声标注；古谱/编辑性标记 | 先扩 ABC、简谱和 OSMD；MEI、古谱和学术编辑候选 Verovio | **P1 附件与导出 / P2 MEI**。Verovio 能把 MEI 等输入刻为 SVG，仍需确定导入子集、字体、LGPL 分发与播放同步；不急于替换所有现有谱面。 |
| 哲学、逻辑学、法学 | 论证—证据—反驳图；自然演绎证明树；条款层级、修订对照与有效期时间线 | Graphviz、Mermaid、Compose 对齐布局、Diff、引文/注释系统 | **P2**。展示推理步骤和支持关系；自动证明检查或法律结论判定属于另一项能力。 |
| 工业设计、建筑、几何教育 | 参数化实体、剖面、布尔组合、带尺寸的模型查看 | SVG/JSXGraph 先覆盖二维；重型候选 JSCAD 建模 + 受控 Three 查看 | **P3**。JSCAD 本身常以 JavaScript 输入，项目接入应另做有界 JSON 到受信任建模调用的转换，不开放模型脚本执行。 |

## 3. 语言学与历史学的首批设计

### 语言学：先把“对齐”做成语义

推荐顺序：

1. **逐词/词素对齐卡片**：把每个词及其释义当成同一个布局单元，支持整体折行或横滑。不能用连续空格和普通 Markdown 表格凑对齐。Leipzig 规则要求逐词纵向对应，词素边界在原文和释义中相互对应。
2. **IPA 与注音**：先验证系统字体覆盖、组合音标、行高、选区和复制；缺字时评估随包/可选的专用字体。Doulos SIL 官方说明包含完整 IPA；不能据此推断所有 ROM 和字体功能已验证。复杂连续 Ruby 可在专用静态排版中评估，不能仅放开原始标签。
3. **句法/依存图**：成分树可从受限树 JSON 转 DOT/SVG；依存图按词序画弧线。CoNLL-U 有十个字段，还包含多词范围 ID、空节点和增强依存，首版应声明解析子集，不能按“每行十列”草率吞掉信息。
4. **语音标注**：短音频区域、转写、波形与声谱图关联。wavesurfer 的 Regions / Spectrogram 可作候选；长音频应避免整段 PCM 解码。频谱可视化不等于 Praat 式声学分析，F0/共振峰需另行定义算法和精度。

建议为原文、词素、释义、译文、语言/方向、例句 ID 和来源分别留字段。左到右与右到左文本应保持逻辑顺序；中文、阿拉伯文与 IPA 混排需要专门样例。

### 历史学：先把“日期和证据”做成语义

- **分组事件时间线**：事件可为点或区间，允许人物、政权、地区分轨。缩放后标签可折叠，展开仍能找到完整名称与来源。
- **公元前与不确定日期**：保存原始纪年、历法、规范化排序值、精度与确定性。EDTF 的 `?`、`~`、`%`、区间和负年份可作交换基础；界面仍需要“约”“可能”“公元前”等说明。
- **不直接交给 JavaScript Date**：历史纪年没有公元 0 年，天文学年号与界面纪年存在映射；还要避开构造日期时 0–99 年的特殊行为。仅有朝代或世纪时，不应伪装成确定的某日。优先以明确年序的数值轴展示，并保留原文。
- **史料关系**：事件、引文、人物和图像区域使用稳定 ID；来源保留作者、题名、版本、页码/卷次和可访问链接。渲染器负责忠实显示输入，不自动鉴定史实。
- **文献/图像批注**：参考 W3C Web Annotation 的 exact/prefix/suffix 或区域定位思路；同时保存消息/附件版本与规范化方式，防止编辑、分支和重复引文让锚点指向错误内容。Unicode 代码点、UTF-16 代码单元和用户可见字素簇不可混用。

vis-timeline 是成熟的交互式点/区间分组组件，但仍须应用提供历史日期层。已有 ECharts 或原生时间线也可实现首版；选库应比较手机手势、可读性和导出成本，而不是只比较演示页效果。

## 4. 如何使用原有实验性成果

| 现有方向 | 可以迁移的部分 | 不能直接宣称支持的内容 |
| --- | --- | --- |
| 正式 `sigil/pattern` | 对称、周期、参数化曲线和受限几何层；可服务平面群、几何艺术与图形教学 | 不是任意 p5 脚本、数值物理引擎或自动科学作图系统。 |
| JSXGraph 随包能力 | 有限几何、方向场和三维视图可先做 schema 小实验 | API 名称存在不等于当前 JSON 已接受；需补 AST、采样预算、就绪、手势和导出测试。 |
| SmilesDrawer 随包能力 | 先验证反应解析和 SVG 合成，减少新增依赖 | 当前只注册单分子入口，不能把 reaction SMILES 直接当已支持格式。 |
| 本地 Three Debug 原型 | 本地模块、受控材质、GPU 截图、生命周期及静态后备的工程经验 | 原实验只验证有限路径网格和局部晶体，不等于已有通用 glTF/STL、蛋白、体数据或 CAD 查看器。 |

Three 依据为本地 `docs/threejs-feasibility-spike.md` 和 `src/debug` 原型；该实验仍未纳入此前的 GitHub 推送范围。本轮没有重跑其旧性能数据。原记录明确提出呈现卡顿和软件 Canvas 无法直接取得 WebGL 画面两个接入阻断点。

建议把 Three 后续任务限定为：**内联静态封面 → 用户打开全屏 → 单个活动 GPU 实例 → GPU 产物回传 → 退出释放**。先完成该契约，再评估分子、晶格或参数实体。不要为每条聊天消息常驻一个 3D 场景。

## 5. 跨学科先做的基础工作

1. **真实脚注与引文**：消息范围内编号和锚点、定义迟到后的流式修正、重复引用返回、缺失定义回退。原始资料可按 CSL-JSON 整理，citeproc-js 为样式处理候选；处理器不会自动查证、补全或修复虚构文献。
2. **附件到已有渲染器**：CSV/TSV、ABC、MusicXML/MXL、GeoJSON 等先复用既有入口；高分辨率 PDF/图像使用分页或分块，不一次性展开成整份位图。
3. **统一产物契约**：区分输入接受、设备能力、绘制完成；保存原文、可访问文本、稳定节点 ID、静态封面、错误态和导出就绪信号。模型工具与围栏共享同一格式校验。
4. **主题与语义颜色**：正文标签保持对比度；化学元素、序列碱基、统计色标等颜色保留图例含义，不被壁纸取色任意改写。科学纸面、深色主题、复杂背景分别检查。
5. **流式与长对话成本**：闭合且校验通过后再初始化重型渲染；内联优先静态产物，滚动和后台暂停，单项失败保留源码。缓存键包含源码与渲染器/schema 版本、主题、尺寸及字体条件。
6. **离线与依赖**：固定版本、本地脚本/字体/Worker/WASM 和许可证；新增专用格式不扩大通用 HTML 的脚本权限。库文件离线不等于远程图片、IIIF 瓦片、地图数据或蛋白坐标已离线。

## 6. 建议的开发批次与验收

| 批次 | 实施内容 | 首批验收样例 |
| --- | --- | --- |
| A：共性 P0 | 对齐目录与围栏限制、绘制状态和源码回退；补真脚注、消息内跳转和跨消息唯一锚点 | 定义迟到、多次引用、同名脚注、代码内字面语法；三条显示路径分别标明覆盖。 |
| B：高收益 P1 | 逐词释义/例句卡；历史多轨时间线；验证 SmilesDrawer 反应图；CSV/TSV 到表格/图表 | 词组折行、RTL/IPA、约略/BCE 区间、长标签、反应电荷/立体标记、缺失数据与单位。 |
| C：专业 P2 | JSXGraph 场与有限三维；Newick 树、序列对齐；MEI；文献对照/IIIF；电路图 | 树的枝长和折叠、已比对序列、长谱分页、古籍区域批注、原理图连接与标签遮挡。每项单独锁版本并检查导出。 |
| D：重型 P3 | GPU 封面与资源契约；再评估 3D 分子、晶格、实体与剖切 | 实际 WebGL 能力、上下文丢失、后台恢复、GPU 截图、低内存降级和退出释放。 |

每批至少覆盖：用户/助手两种消息来源、流式结束与取消、长对话快速滚动、深浅色/壁纸背景、大字体、全屏返回、重开历史、失败后源码，以及 PNG/PDF 等实际承诺的导出格式。长内容应检查首尾段/页都存在；不能用非空截图代替语义正确或完整导出。

本轮不做包体、内存或帧率的无依据数值估计。候选库的桌面浏览器示例不代表已经适配 Android WebView。

## 7. 官方与项目来源

来源于本轮 AnySearch / Tavily 检索后读取的页面。规范用于定义数据语义，组件项目用于评估实现；“开源可用”不等于当前锁定版本已通过接入验收。

| 来源 | 本次用于确认 |
| --- | --- |
| [Leipzig Glossing Rules](https://www.eva.mpg.de/lingua/resources/glossing-rules.php) | 逐词垂直对齐、词素和附着词边界对应。 |
| [UD CoNLL-U](https://universaldependencies.org/format.html) | 十字段、多词 ID、空节点与增强依存。 |
| [Doulos SIL](https://software.sil.org/doulos/) | IPA 覆盖及组合附加符号定位等字体能力；分发前锁定字体与许可证。 |
| [W3C Ruby](https://www.w3.org/TR/css-ruby-1) / [Writing Modes](https://www.w3.org/TR/css-writing-modes-3) | 注音与竖排的布局语义。 |
| [TEI Critical Apparatus](https://www.tei-c.org/release/doc/tei-p5-doc/en/html/TC.html) | lemma/readings/witnesses 与校勘关联方法，先支持子集。 |
| [EDTF](https://www.loc.gov/standards/datetime/edtf.html) | 时间精度、区间、不确定和约略标记、负年份。 |
| [vis-timeline](https://visjs.github.io/vis-timeline/docs/timeline) | 点/区间、分组、拖动缩放与 DOM 性能注意事项。 |
| [IIIF Presentation 3.0](https://iiif.io/api/presentation/3.0/) / [OpenSeadragon](https://github.com/openseadragon/openseadragon) | 复合资料与批注关系；高清可缩放图像查看。首版目标应明确为某个 IIIF 子集。 |
| [Web Annotation](https://www.w3.org/TR/annotation-model/) | 原文、前后文选择器，以及逻辑顺序和字符位置语义。 |
| [JSXGraph View3D](https://jsxgraph.org/docs/3d_view3d.js.html) | 现有引擎的三维视图方向；MIT/LGPL 双许可，项目既有打包使用 MIT。 |
| [Vega-Lite Error Bar](https://vega.github.io/vega-lite/docs/errorbar.html) | 误差棒和置信区间已有声明式能力，示例远程 data URL 仍须改为项目允许的内联数据。 |
| [SmilesDrawer](https://github.com/reymond-group/smilesDrawer) | 单分子 SVG 与 MIT 项目；反应 API 存在另以本地 2.4.1 实际导出核对。 |
| [RDKit.js](https://rdkitjs.com/) | 官方 JavaScript MinimalLib 子集、WASM 与 BSD-3-Clause；需要验证 WASM exceptions 支持及显式对象释放，不能套用全部 Python API。 |
| [3Dmol.js](https://3dmol.csb.pitt.edu/doc/index.html) | WebGL 分子查看、PDB/mmCIF/SDF/XYZ 等格式与触屏支持；不是完整化学计算工具。 |
| [phylotree.js](https://github.com/veg/phylotree.js) | Newick 等树格式、SVG、枝长显示、折叠与选择。 |
| [MSAViewer](https://msa.biojs.net/) | 浏览器展示已比对的 FASTA/Clustal 数据；仅列为参考，维护与依赖须另核验。 |
| [wavesurfer 性能文档](https://wavesurfer.xyz/docs/performance) | 音频解码内存、声谱 FFT/画布成本、实例与监听器释放。 |
| [Verovio](https://github.com/rism-digital/verovio) / [编码格式](https://book.verovio.org/interactive-notation/encoding-formats.html) | MEI 到 SVG、MusicXML 等输入转换、JavaScript 工具包及 LGPL。 |
| [circuit-to-svg](https://github.com/tscircuit/circuit-to-svg) | Circuit JSON 到原理图/PCB/系统等 SVG；生产者与布局数据约束需要另外适配。 |
| [JSCAD](https://github.com/jscad/OpenJSCAD.org) | 参数化二维/三维建模与 MIT 项目；应用需约束输入和复杂度。 |
| [citeproc-js](https://citeproc-js.readthedocs.io/en/latest/) / [CSL-JSON](https://citeproc-js.readthedocs.io/en/latest/csl-json/markup.html) | 文献数据和引文样式处理；格式化不等于文献真实性校验。 |
