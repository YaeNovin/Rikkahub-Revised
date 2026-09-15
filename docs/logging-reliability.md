# 日志可靠性与 Gemini 混合工具回归

## 崩溃线索

用户导出日志中，`ComposeRuntimeError: pending composition has not been applied` 的路径为：
原生视图移除 → `rootViewRequestFocus` → 焦点搜索 → LazyColumn 越界测量 → 重入子组合。
与 [Google Issue 507508113](https://issuetracker.google.com/issues/507508113) 一致。

内联 WebView 宿主阻止原生子视图获取输入焦点，保留触摸、链接、缩放；需要输入焦点的 HTML 表单可在全屏页面使用。
只读 TextView 也不再因 LinkMovementMethod 自动成为输入焦点目标。不在 `onRelease` 中清焦点，也不捕获并忽略 Compose Runtime 错误。
崩溃堆栈保留上限由 8,000 增至 32,000 字符，便于查看底部调用链。

## 长菜单测量崩溃（2026-09-14 修复）

另一条日志的 `Can't represent a width of 32767 and height of 51072 in Constraints`
来自下拉菜单固有尺寸预测穿过背景层与整张长列表。`MenuViewport` 限制预测及真实测量尺寸，
背景只按可见视口绘制，列表在内部单独滚动，普通与 Exposed 下拉菜单共用该路径。
修复后在 V2301A 的独立 Debug 包通过两项设备回归：600 项带背景菜单、500 项无背景选择器，
末项均可选择；前者滚动前后固定位置的背景采样像素一致。测试源码随仓库保留，截图和日志仅本地保存。

## 日志保存边界

- 请求日志与供应商参数日志保存在应用私有 `files/logs/requests`，不使用缓存目录。
- 每条请求一个文件；响应正文完成后覆盖同一 ID。临时文件同步后原子替换，写入失败保留上一版本。
- 最多 500 条／32 MiB，按最近写入时间清理较早文件。错误日志维持 7 天／200 条，普通事件仍为会话内最近 100 条。
- 不依赖退出回调；已完成的写入跨进程保留。进程在响应读完前被杀时，仅保证此前已保存的请求元数据，不保证未读完正文。
- 原有内存日志在退出前未曾写盘，无法追溯恢复。新日志继续沿用捕获层的凭据屏蔽；完整日志仍可能含对话隐私。
- 清空同时清除内存与对应磁盘记录；不会删除聊天数据。导出沿用用户选择保存位置及完整／脱敏模式。

## Gemini 工具协议

参考 [混合工具官方说明](https://ai.google.dev/gemini-api/docs/generate-content/tool-combination) 与 [generateContent API](https://ai.google.dev/api/generate-content)。
在最终请求（包括自定义字段合并后）同时含函数声明与支持的内置工具时，发送 `toolConfig.includeServerSideToolInvocations=true`，保留其他工具配置。

`toolCall`／`toolResponse` 按服务端工具保存，不加入本地执行和审批队列。原始块、ID、签名及与文本交错的顺序保留供续轮回传；界面不重复展示已配对的结果块。
旧 Gemini 系列原有的冲突回退不变，单一工具类型不主动增加混合工具字段。

## 展示与验证

响应正文复用富内容表面颜色与透明描边，长内容默认折叠，展开后按需显示分段。
`body.systemInstruction` 的标准文本部分解码换行后展示；未知扩展字段仍保留 JSON 视图。复制、导出不使用展示分段替换原文。
JSON 树长字符串只展示摘要，点击后分段阅读，颜色跟随主题。

定向测试覆盖日志重新打开、正文更新、损坏单条记录、失败写入、存储上限与清空，Gemini 配置合并及流式／非流式回传，以及无损文本分段。
设备回归测试 `WebViewNavigationRegressionTest.inlineAutofocusCannotReenterLazyCompositionDuringStreamingReplacement` 覆盖内联自动焦点与列表替换；编译通过不代表已在用户设备复现验证。
