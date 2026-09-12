# 聊天建议 / Chat suggestions

聊天建议现在使用统一的本地契约，生成模型可以通过 `get_session_capabilities` 查询当前会话实际可用的显示方式、数量与长度限制、分类、动作和参数表单规则。查询工具默认返回精简信息；传入 `include_suggestion_details: true` 才会展开响应 JSON 结构和示例。

Chat suggestions use one local contract. A model can call `get_session_capabilities` to inspect the active display mode, limits, categories, actions, and draft-form rules. The tool returns a compact result by default; pass `include_suggestion_details: true` to receive the response schema and example.

「更多 → 聊天建议」面板使用一个纵向滚动容器，标题、操作、建议卡片及运行摘要随面板整体滚动，取消内层 320dp 列表限制。聊天页底部的紧凑建议栏仍横向滚动，并服从外观设置中的高度限制。

The More → Chat suggestions panel uses one vertical scroll container for its
heading, actions, cards, and run summary. The compact chat dock keeps horizontal
scrolling and its configurable height limit.

建议始终作为草稿处理：点击建议只会预览或插入输入框，不会自动发送消息、执行工具、搜索或修改工作区。需要用户输入的建议可以使用 `text`、`single`、`multi`、`slider`、`rating`、`date` 和 `time` 字段，并通过 `{{question_id}}` 插值；确认、危险操作和倒计时字段不属于建议表单。

Suggestions are drafts. Selecting one previews or inserts text into the composer and never sends a message, executes a tool, searches, or changes a workspace automatically. A suggestion may use `text`, `single`, `multi`, `slider`, `rating`, `date`, or `time` fields and reference them with `{{question_id}}`; confirmation, dangerous-operation, and countdown fields are intentionally excluded.

建议表面会复用当前背景下的主题表面和富内容描边。启用背景及富内容特效时，使用受限透明度的半透明表面；关闭任一项时回退到普通主题表面。组件不会在滚动区域内重新绘制壁纸，因此不会遮挡消息正文，也不会改变背景图、遮罩或虚化设置。

Suggestion surfaces reuse the themed surface and rich-content outline over the current backdrop. When background and rich-content effects are enabled, the surface uses bounded translucency; otherwise it falls back to the normal theme surface. The component never redraws the wallpaper inside a scrolling region, so it does not cover message content or alter wallpaper, overlay, or blur settings.
