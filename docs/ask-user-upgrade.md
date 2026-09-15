# ask_user 交互契约与协议适配

`ask_user` 使用共享的版本 2 契约：工具声明、能力查询、参数校验与界面读取同一组字段。旧对话中的回答仍以 `{"answers": {...}}` 保存，返回模型时补充 `status`、`question_types` 和 `question_presentations`。

## 模型如何发现交互能力

- `ask_user` 的工具说明与 JSON Schema 直接列出问题类型、展示形式、适用字段与限制。
- `get_session_capabilities` 报告当前会话是否启用该工具。传入 `include_ask_user_details=true` 可获取完整参数和示例；默认只返回简要能力。
- 支持 `text`、`single`、`multi`、`slider`、`rating`、`confirm`、`date`、`time`。日期/时间使用 Android 选择器，返回设备本地的 `YYYY-MM-DD` / `HH:mm`，不隐含时区转换。
- 文本建议、单选和多选支持 `presentation=auto/chips/list/cards`。自动模式根据图片、徽标、选项数量及长度决定布局；超过 8 项出现搜索框。富选项的返回值始终是 `value`，不会变为展示名称。

```json
{
  "questions": [
    {
      "id": "mode",
      "question": "选择模式",
      "selection_type": "single",
      "presentation": "cards",
      "options": ["simple", "advanced"],
      "option_details": [
        {"value": "simple", "label": "简单", "icon": "⚡", "badge": "快捷"},
        {"value": "advanced", "label": "高级"}
      ]
    },
    {
      "id": "ratio",
      "question": "设置比例",
      "selection_type": "slider",
      "min_value": 0,
      "max_value": 1,
      "step": 0.0001,
      "default": "0.1234",
      "visible_if": {"question_id": "mode", "value": "advanced"}
    }
  ]
}
```

## 参数边界与兼容

- 每次最多 32 个问题、每题最多 64 个选项，手机上建议 1–5 个问题。
- 对外声明的 `default` 为字符串；多选使用 `default_values` 字符串数组，避免含逗号的选项歧义。旧数字、布尔和数组默认值仍兼容解析，但确认题默认值不构成同意。
- 数字绝对值不超过 `1e12`，步长不小于 `1e-9`；还限制区间数量与实际数值精度。滑块以归一化位置驱动，十进制吸附与格式化使用 BigDecimal，另提供精确输入。星级评分最多 10 档。
- `visible_if` 支持单条件和 `all/any/not` 组合。复杂嵌套可用 `root + nodes` 扁平条件图，避免递归 JSON Schema：最多 32 个节点、深度 8、展开引用 512 次；拒绝循环、未知引用及无用节点。兼容旧嵌套表达式。
- 隐藏字段不会提交，也不会用于唤醒下游字段；字段不适用、非法默认值、未知参数和无效可选回答会返回明确错误，不能静默忽略错误回答。
- Google 的 Schema 转换保留字符串枚举及可选字段，数字枚举转换为说明，避免误删名为 `enum` / `format` 的业务字段。
- OpenAI Responses 中 `ask_user` 显式 `strict=false`，避免把可选字段改为必填。OpenAI Chat/兼容协议和 Anthropic 使用常规工具 Schema，不统一强制严格模式。DeepSeek 的 Beta strict 模式不自动启用。

## 倒计时与草稿

- 普通确认可无倒计时；`danger=true` 默认 30 秒，或显式设置 `timeout_seconds`。各确认题在按钮进入可视范围且页面处于前台展开状态后独立计时。
- 计时开始后，折叠、切页和切后台不会暂停或重新计时。同次开机使用单调时钟，设备重启后使用持久化的墙钟截止时间。整个问答最长等待 30 分钟。
- 未作选择而超时视为 `false`；截止前明确点击的答案可保留到提交。隐藏或清除确认字段会撤销其旧确认，不重新延长截止时间。问答确认不替代工作区等执行工具自己的审批。
- 草稿保存在本地工具 metadata，与对话、工具调用和输入指纹绑定，150ms 防抖写入，离开时刷入。提交等待先前写入，并在持久化锁内依据最新草稿重新校验。终态移除草稿，历史展示读取正式回答。
- 单次回答总长限制为 64 Ki 字符，单个文本为 16 Ki 字符；总长超限在界面提示，不能提交。草稿和计时信息不进入 Provider 请求正文。

## 官方依据与验证边界

- [Google function calling](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling)：已读取函数参数、字符串枚举及 required 示例。
- [Anthropic 工具定义](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools) 与 [strict tool use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/strict-tool-use)：已读取普通 Schema 与可选严格模式的区别。
- [DeepSeek tool calls](https://api-docs.deepseek.com/guides/tool_calls/)：已读取常规调用和 Beta strict 的约束。
- [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling)：检索到了官方结果，但本次正文抓取失败；不声称完成了最新文档的全面核验。

回归测试覆盖共享字段/示例、Google/Anthropic/OpenAI Chat/Responses 请求序列化、兼容通道、能力开关、精度、条件联动、独立计时、超时审批和草稿恢复；另有独立 Android Room 关闭重开测试。请求构造测试不调用付费 API，也不能证明所有第三方网关均接受该 Schema。真机安装与实际交互验证结果需以本次运行报告为准。

2026-09-10 验证结果：`:app:compileQaKotlin` 通过，相关 JVM 测试 41 项全部通过；独立 Debug 回归包及 instrumentation 包安装成功，在 Android 16 真机上运行 Room 持久化测试 1 项通过。该测试使用独立临时数据库并在结束后删除自己的测试库，不清除原 QA 应用数据。真实模型端到端问答和完整 UI 手势流程尚未在本轮验证。

## 2026-09-15 参数兼容补充（开发源码，未发布）

`AskUserProtocol` 对明确的函数参数封装、JSON 字符串编码、单个问题对象及问题数组进行归一化，
随后仍使用同一份字段校验；归一化后仍缺少 `questions` 或问题内容不合法时不执行问答。
Gemini 流式解码保留无名参数续片，不把空参数起始帧当作完整问题。
通用工具参数解包与 `ask_user` 专用归一化分别处理，不能把任意其他工具的参数当作问题。
详情和测试边界见 [2026-09-15 源码更新](changes-2026-09-15.md)。
