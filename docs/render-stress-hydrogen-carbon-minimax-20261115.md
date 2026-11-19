# 长输出渲染压力测试与模型参数适配

## 测试方法

在 USB 真机 vivo V2301A／Android 16 上，使用独立包名 `me.rerere.rikkahub.revised.renderperf` 的 Debug 测试入口，复用生产 `ChatList`、`ChatMessage`、Markdown、代码高亮及图形渲染组件。由真实 Choreographer 驱动，无 Compose 虚拟时钟，无供应商生成或搜索请求，不写入用户会话数据库／助手配置。

最终固定输出 **149102 字符、149122 UTF-8 字节、23391 个空白分隔词**，其中正文包含 19200 个英文词，另有代码、表格、公式、图形定义和本地图片。它是超过 16K 规模的合成文本，未将字符数／单词数冒充某家供应商 tokenizer 的精确 token 数。每 50 ms 最多追加 1024 字符，完成后复制为 **五条完全相同内容**的助手消息，消息 ID 各异。基线为 148749 字符；353 字符差异来自补全 MusicXML 示例的声部与音符。

内容覆盖目录中的 21 种渲染格式：Markdown、LaTeX、代码、Diff、HTML、SVG、Mermaid、ECharts、ABC、简谱、Leaflet、Railroad、WaveDrom、Graphviz、Vega、SMILES、MusicXML、Terminal、Pattern、Math Plot、Sigil，另含本地 PNG 图片和格式化 JSON。HTML 遵循应用的手动静态预览规则。

输出后执行 16 次 120 ms 快速往返、逐条切换五条长消息并各滑动 3 次，再向回滑动 8 次。通过 UI 树确定手势区域；切换整条长消息用于避免几十次手势只停留在同一条长回复内。采集独立的流式／复制滑动 Perfetto、gfxinfo、framestats、每约 3 秒的 meminfo、logcat、退出记录与截图。复测额外确认最后的 END 标记可见，避免把内容未完整挂载当作性能改善。

驱动：`scripts/qa_render_stress.py`；入口仅在 `src/debug`，QA／Release 不包含。基线和复测工件：`build/reports/render-stress-20261115/`。Debug、首次 JIT、缓存、ROM 调度和 UIAutomator 会影响绝对数值，结果不能代表所有生产设备。

## 发现与修复

基线流式输出 P95 250 ms、P99 350 ms，280 帧中 112 卡顿帧；最长主线程 doFrame **2100.330 ms**。该帧包含约 1030 ms 重组合、900 ms 布局，1318 次文本测量。复制消息后的滑动 P95 36 ms、P99 77 ms，但切换消息有 **1849.576 ms** 的单帧停顿。

Markdown 含图片时采用 HTML DOM 渲染路径。旧实现对新的完整文档树重新组合；长内容首次进入视口还会集中挂载上千个文本布局。

修复：

- 在后台准备顶层段落快照，对照实际渲染后的 HTML（包括属性和引用链接）复用未变化块，尾部变化和围栏闭合重新生成。
- 复用节点显式脱离父文档，避免通过 parent 链保留历次完整流式文档。单元测试验证旧文档不被修改、引用链接更新、围栏闭合与父引用释放。
- 保持同一个原生 SelectionContainer，流式结束不再把整条回答搬入新父节点。移除了实验中分批切换选择注册器的处理，最终代码不依赖 Compose 内部选择接口。
- HTML 内容首次挂载 16 块，后续每个真实帧最多追加 32 块；选择随内容原生注册，避免先挂载、再重建选择布局。导出与返回布局就绪检查等待全部块挂载，不截断输出、不用省略文本替代内容。克隆节点不再有顶层兄弟引用，因此额外传递后续块状态来保留原有段落间距。
- 非自动换行代码块的行号合为一个多行文本布局，避免每个数字单独测量；移除代码文本的尺寸动画，减少重复布局。自动换行仍逐行对齐。
- MusicXML 目录示例补全 part-list 与 Piano C4 音符，修复实际渲染错误。HTML 静态预览添加可被作者样式覆盖的默认白色纸面，修复深色页面中默认黑字叠在透明底色上的不可读问题；SVG 继续透明。

## 真机性能结果

基线 `before/` 与最终 `release-candidate/` 各执行一轮同流程，最终版本 `2.4.8-revised.9-20261115-131338-debug`，PID 6686。gfxinfo 是设备图形帧统计；最长主线程帧来自对应 Perfetto 的 Choreographer slice，两者不是同一统计量。

| 阶段与指标 | 基线 | 最终 |
| --- | ---: | ---: |
| 流式帧数／卡顿帧 | 280 / 112 | 256 / 134 |
| 流式 P50 / P95 / P99 | 18 / 250 / 350 ms | 20 / 200 / 350 ms |
| 流式最长主线程帧 | 2100.330 ms | 233.679 ms |
| 复制滑动帧数／卡顿帧 | 901 / 76 | 819 / 92 |
| 复制滑动 P50 / P95 / P99 | 17 / 36 / 77 ms | 15 / 250 / 450 ms |
| 复制滑动最长主线程帧 | 1849.576 ms | 578.061 ms |
| PSS 首次／峰值／末次 | 314984 / 564049 / 471383 KB | 318919 / 515749 / 459562 KB |

流式约 11.96 秒完成，末尾 END 标记实际可见，随后五条复制消息均被遍历。同一进程贯穿流式与复制滑动，crash buffer 无该进程崩溃，退出记录没有本轮崩溃／ANR；其他旧的主动停止与安装更新记录不算测试崩溃。PSS 峰值约 504 MiB，较基线约 551 MiB 下降；单轮 PSS 不能证明没有泄漏，本轮未做保留对象堆分析。

**长停顿峰值明显下降，但整体尚未达到流畅通过标准。** 首次跨越超长消息仍会触发大量布局和绘制；复制滑动尾延迟比基线高，卡顿率由 8.44% 变为 11.23%，流式卡顿率也未改善。不能只引用最长帧下降而宣称全部性能指标变好。Debug/JIT、缓存和 ROM 调度存在单轮波动，后续应继续按可见区域拆分超长消息布局并用生产构建多轮对照验证。

实验目录 `after/`、`verified/`、`completed/`、`native-selection/` 用于保留迭代证据，均不是最终数值；`final/` 因当时安装未完成而误测旧版本，已作废。最终驱动启动前严格比对已安装 versionName 和本地 APK metadata，安装失败／版本不符直接终止。

## Gemini 4 Hydrogen／Carbon

官方来源：

- [Hydrogen 更新与参数](https://aistudio.google.com/docs/whats-new-gemini-4-hydrogen)
- [Hydrogen 模型规格](https://aistudio.google.com/docs/models/gemini-4-hydrogen)
- [Carbon 更新与参数](https://aistudio.google.com/docs/whats-new-gemini-4-carbon)

新增模型注册及显示名称匹配，保留实际请求 ID：

| 模型 | 思考档位 | 默认 | 上下文 |
| --- | --- | --- | --- |
| Gemini 4 Hydrogen | low / medium / high / xhigh | high | 输入、思考和输出共享 1048576 |
| Gemini 4 Carbon | low / medium / high | medium | 同上 |

沿用现有 Gemini 4 原生长输出续写、工具组合、结构化输出、媒体分辨率、停止序列与安全设置。扩展参数页显示对应档位与默认值；Google 原生及 OpenAI Chat Completions 兼容映射均验证 Hydrogen 的 xhigh，不被旧规则错误降档。已停用的采样参数与显式缓存不发送。Responses 接入不推测 Google 原生参数的转发方式。

## MiniMax 参数页

官方来源：[OpenAI 兼容参数](https://platform.minimax.io/docs/api-reference/text-openai-api)、[Anthropic 兼容参数](https://platform.minimax.io/docs/api-reference/text-anthropic-api)。

新增 MiniMax 模型家族、助手设置入口和独立持久化选项；切换到其他家族时不显示。专有控件按本次官方文档确认的 M3 契约开放：

- **M3.1 Flash**：常开思考，提供 low / medium / high / xhigh / max，默认 max。OpenAI 映射 reasoning_effort；Anthropic 映射 adaptive 与 output_config.effort。不会发送禁用思考、service_tier 或不支持的 reasoning_split 覆盖。
- **M3**：思考默认／adaptive／disabled；standard／priority 服务等级（界面注明 priority 价格差异）；OpenAI 的 reasoning_split 格式选项，Anthropic 不发送该字段。AUTO 时保留两种协议不同的默认思考行为。
- 基础页对 M3 系列固定采样参数禁用编辑；maxTokens 沿用基础配置。旧 M2 模型或 Responses 通道保留普通参数机制，专有控件不冒充已确认支持。

所有新参数均经助手模型对象传入实际生成请求并可序列化；未执行收费供应商实网生成。

## 最终验证

- 构建：`build/reports/qa-build/20261115-131323/attempt-1.log`，Debug、QA 和 App JVM 测试构建成功。AI 493 项、App 1199 项，共 **1692 项**，失败／错误／跳过均为 0。HTML 旧透明背景断言已随默认纸面行为更新，并增加作者样式优先与 SVG 透明回归覆盖。
- 模型表单真机验证：Hydrogen 可选 xhigh 并保存小写 `xhigh`；Carbon 不出现超高档；MiniMax M3 可保存 adaptive / priority / reasoningSplit。证据见同目录 `hydrogen-selected.json`、`carbon-thinking.xml`、`minimax-selected.json` 与截图。没有付费供应商生成请求。
- 渲染器：`gallery/` 保留 21 格式分别启动的截图／日志。最初 MusicXML 示例确有错误，已修复并在 `musicxml-fixed.png` 目视确认乐谱；`html-fixed.png` 确认手动预览的正文和底色可读。普通 Markdown、LaTeX 代码围栏保持原有源码显示规则，样本另有真实正文和公式。
- 长按正文已目视看到选区与手柄（`selection-start-menu.png`）；本机浮动菜单只呈现“全选”，未将其记为“选区复制已验证通过”。完整消息与代码另有原有复制按钮。本轮未执行新增 instrumentation APK 的选择回归测试。
- QA 覆盖安装与设备设置恢复记录在 `build/reports/render-stress-20261115/qa-install-verification.json` 中，主包只使用 `install -r`。
- USB 覆盖安装返回 Success，正式 QA 版本 `2.4.8-revised.9-20261115-131338`；首次安装时间仍为 **2026-10-17 22:49:10**，本次更新时间 2026-11-15 13:23:42。正式 QA 已回到前台，独立压力包已停止。保持亮屏设置恢复为 0，自动锁屏仍为原值 600000 ms。
