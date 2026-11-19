# 对话富文本检查与 HEX / 双引号装饰

日期：2026-11-16。范围为 Android 对话里的原生 Markdown、原生 HTML 与简化 HTML 显示路径。

## 输入、输出与显示边界

| 路径 | 检查结果与本次处理 |
| --- | --- |
| 输入与编辑 | ChatInput 使用 TextFieldState，ChatInputState 保留文本与附件；编辑、输入法组合和发送使用原始文本。本次装饰不插入输入状态。 |
| 模型请求与流式输出 | 输入 transformer、输出 visualTransform / onGenerationFinish 和 UIMessagePart.Text 分工已有边界；新功能位于显示侧，不修改供应商请求、持久化消息或流式增量。 |
| 用户与模型消息 | ChatMessage 的两类正文都进入 MarkdownBlock，使用相同设置与识别逻辑，保留各自气泡的背景/前景采样。 |
| Markdown 与 HTML | 原生 AnnotatedString 渲染和 HTML 转原生渲染共用 LinkText 装饰。含代码、公式和已有内联内容的区域带排除标记；HTML 属性与链接目标不作为可见正文扫描。 |
| 选择、复制与导出 | 整条消息复制仍使用 message.toText；色块只对原有 HEX 范围增加内联内容注解，AnnotatedString.text、链接范围和原有样式不改写。Canvas 不提供第二份可选择文本。 |
| 设置与重组 | 两项设置只改变显示装饰，不改 Markdown 解析缓存键，不增加按设置值重建页面的 key；开关使用当前 DisplaySetting 的字段级更新。 |

主要入口：

- [ChatInput.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt)
- [ChatInputState.kt](../app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt)
- [Transformer.kt](../app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)
- [ChatMessage.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt)
- [LinkText.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/LinkText.kt)

## 新增行为

“设置 → 偏好设置 → 常规设置”增加两个默认开启、可独立关闭的选项：

1. **HEX 色彩预览**：正文中的 `#RRGGBB` 旁显示方形色块，接受大小写十六进制。色块填充来自 RGB 原值，不对其进行饱和度或取色调整。边长按所在文字的有效字号计算，跟随字体缩放；细边界让纯白/纯黑色块在同色背景上仍可辨认。
2. **双引号内容高亮**：支持 `"…"`、`“…”`、`＂…＂`、`«…»`、`„…“`。强调内部文字，保留引号本身；单引号、弯单引号和反引号不作为分隔符。允许段落内换行、嵌套和跨粗体/链接片段；未配对的流式引号等待配对后再生效。

HEX 暂不接受 `#RGB`、带 alpha 的八位形式或六位之后仍接 ASCII 标识符的串。普通中文可紧邻颜色，例如 `颜色#A1b2C3，`。代码块、行内代码、公式、已有内联引用，以及 URL 地址保持原来的显示语义。

高亮色优先取当前文字配色的实际种子，其次取当前正文的有彩色前景；选中模式为中性/无彩色时，使用当前主题强调色作为色相来源。HCT 保留来源色相、提高 chroma，并按实际气泡/图片采样和 HTML 局部背景选择 tone。2026-11-16 后续修订改为在普通文字 4.5:1 对比度约束内优先选择实际色域中的鲜艳颜色，同时参考正文颜色避免强调色趋同。复杂图片无法兼顾所有像素的对比度和鲜艳度时，先筛选实际 chroma 至少 30、tone 至少 35 的候选色，再保留其中至少九成最佳样本覆盖并优先满足代表亮度；不宣称所有像素均达标。均匀背景优先满足 4.5:1。不使用纯黑/纯白回退，不加字体描边或保护底板。

引号高亮保留链接及其他样式，字重至少为 SemiBold，已有更粗字重不降低。色块的 HEX 文字继续采用所在位置的字体和文字样式，包括引号中的强调色。

代码渲染器提供禁止正文装饰的作用域，Markdown 的再次组合与嵌套预览均尊重该边界；代码围栏中的语法颜色不受引号高亮开关影响。模型和用户的普通正文共用高亮规则，流式引号在配对完成后生效。

## 实现与资源开销

- [ChatInlineAnalysis.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/ChatInlineAnalysis.kt)：在显示文本上识别 HEX、引号和排除范围，保留 UTF-16 偏移。连续反斜线只在遇到引号时检查，避免普通扫描发生重复回溯。
- [ChatInlineDecorations.kt](../app/src/main/java/me/rerere/rikkahub/ui/components/richtext/ChatInlineDecorations.kt)：在原有 HEX 字符范围上添加内联注解，绘制色块和文字；引号强调按解析后的样式区间处理局部背景，不覆盖粗体/链接语义。
- [ChatQuoteAccent.kt](../app/src/main/java/me/rerere/rikkahub/ui/theme/ChatQuoteAccent.kt)：色相来源、对比度与鲜艳度计算，使用最多 64 项缓存；没有引号时跳过强调色计算。
- TextMeasurer 使用局部缓存；每次组合检查字体解析结果，使异步载入的自定义字体能更新 HEX 尺寸。识别结果按文本和两项开关记忆，调色不会重新解析 Markdown。

旧设置文件缺失新字段时使用默认开启值；显式关闭值可以序列化往返，不需要破坏性的设置迁移。开关位于 [SettingPreferencesGeneralPage.kt](../app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt)，持久字段位于 [PreferencesStore.kt](../app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt)。

## 验证

新增 15 项 JVM 测试：

- HEX 精确六位、大小写、非法长度/字符、中文边界和原始 RGB。
- 多种双引号、单引号不匹配、转义、嵌套、段落内换行与未完成输出。
- 开关独立性、旧设置默认值、关闭后的序列化往返。
- HEX 注解的原文、链接、偏移与样式保留。
- 实际 Markdown / HTML / 简化 HTML builder 的代码排除、HTML 属性排除和局部背景对比度。
- 超过 16K 的文本和 1,000 个重复颜色/引号片段。
- 浅色、深色、AMOLED、多种取色色相及复杂背景样本。

相关已有回归包含可读颜色、文字取色、LaTeX、原始 XML 和 HTML 流式复用，共计 44 项。自定义字体测量调整后的最终 44 项全部通过，QA 编译成功；`git diff --check` 通过。

QA 版本为 `2.4.8-revised.9-20261116-031024`、versionCode 185：

- arm64-v8a 安装包（本地生成，不随源码上传）
- 通用安装包（本地生成，不随源码上传）

结果和安装包 SHA-256 见 验证产物（本地证据，未公开）。本轮没有安装应用或调用真实模型；本地测试不等同于真机的视觉、选择手柄与触控验收。
