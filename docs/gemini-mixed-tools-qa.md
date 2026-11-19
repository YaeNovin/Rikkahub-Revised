# Gemini 内置搜索与函数调用：QA 修复与验证

状态：2026-09-23，开发验证，未发布正式版本。

## 报错与处理范围

用户报告：`Please enable tool_config.include_server_side_tool_invocations to use Built-in tools with Function calling.`

Google generateContent 的内置搜索与客户端函数工具混用时，需要开启上下文回传，并使用兼容的函数调用模式：

```json
{"toolConfig":{"includeServerSideToolInvocations":true,"functionCallingConfig":{"mode":"VALIDATED"}}}
```

当前源码及工作区保留的旧常规 QA APK 已包含基础补字段逻辑。因此，仅凭该错误不能证明是旧包未包含修复，也不能确定报错配置是否经过了 Gemini 兼容中转。需要用新 QA 包在相同模型/供应商配置下复测。

本轮处理：

- 保留聊天流式和非流式共用请求构造，在自定义请求体合并后强制保证混用标志为 true。
- 合并 `tool_config` / `toolConfig` 的其他配置，避免一个别名覆盖另一个别名的有效内容；移除旧标志别名，最终仅发送标准字段。
- 本轮成功读取官方正文后补正：混用默认 `VALIDATED`，不支持显式 `AUTO`。在上下文回传开启时将未设置模式或 `AUTO` 转为 `VALIDATED`；用户明确设置的 `ANY`、`NONE`、`VALIDATED` 和函数白名单保持不变，普通函数调用的 `AUTO` 不受影响。
- 在合并两种根字段之前，统一 `function_calling_config` / `functionCallingConfig`、`allowed_function_names` / `allowedFunctionNames`，避免高优先级的 `NONE` 被另一拼写中的 `AUTO` 覆盖。
- 独立检查函数声明的两种字段拼写，避免空值或空数组遮蔽另一拼写中的有效声明。
- 补齐官方支持的 `fileSearch` / `file_search` 混合工具检测，不新增默认启用的 File Search 工具或 UI 开关。
- 补齐图片生成/编辑请求的相同处理；自定义请求体额外混入函数工具时，也经过混用配置检查。
- 仅有内置工具或仅有客户端函数时，不额外启用此标志；如果用户显式开启上下文回传，同样遵守模式约束。
- 请求诊断新增函数数量、是否包含内置搜索/URL 上下文、混用标志值及最终函数调用模式。不记录提示词、工具参数正文或 API 密钥。
- 不迁移到 Interactions API，不修改用户选择的模型或供应商地址，也不取消搜索/函数工具功能。

参考：[Google generateContent 工具组合说明](https://ai.google.dev/gemini-api/docs/generate-content/tool-combination)、[函数调用模式说明](https://ai.google.dev/gemini-api/docs/generate-content/function-calling)。此前正文抓取超时；2026-09-23 本轮重试中，外部提取服务仍失败，但官方页面直连恢复 HTTP 200，已实际读取并完成对照。

| 官方要求 | 当前处理 |
| --- | --- |
| 混用仅支持 Gemini 3 系列；开启上下文回传 | 保留现有模型分支；在最终混用请求强制开启该标志 |
| 上下文回传开启时默认 VALIDATED，不支持 AUTO | 修复模式规范化，保留显式 ANY/NONE 和白名单 |
| 后续轮次回传工具调用 ID、原始服务器工具部件和思考签名 | 现有搜索/URL 等 `toolCall`、`toolResponse` 及函数响应回放路径保留，并继续运行对应回归测试 |
| File Search 属于可参与组合的服务器工具 | 纳入混合请求检测；不自动提供用户未配置的工具 |

本轮 ADB 只进行了设备与安装版本的只读检查。由于文档已经连通，没有触发用户约定的“仍超时才安装”分支，不安装或启动旧 QA 包进行真实模型调用。

## 回归与 QA 构建

文档对照后的 Gemini 相关 57 项定向 JVM 测试已通过（56 项 Google 提供商测试及 1 项消息元数据测试）：组合配置、请求参数、图像请求、推理请求、工具上下文回传、流式解码，以及 VALIDATED 模式、白名单与别名优先级等。HTTP 拦截测试调用非流式提供商入口，捕获 Google/Vertex 最终序列化请求后在本地终止，不连接真实服务。流式路径使用同一请求构造，并验证流式诊断及服务器工具回放。

应用层 59 项聊天/生图工具回归通过，合计 116 项测试，失败、错误、跳过均为 0。`git diff --check` 通过。常规 QA 与独立测试包均重新构建成功，已核对 ARM64 APK 的真实 Manifest、签名，以及本轮模式修复和诊断字段确实包含在 DEX 中。

| 构建 | 包名 | Manifest 版本 | ARM64 输出 |
| --- | --- | --- | --- |
| 常规 QA | `me.rerere.rikkahub` | `2.4.8-revised.9-20260923-214954` | `app/build/outputs/apk/qa/app-arm64-v8a-qa.apk` |
| 此前独立测试包 | `me.rerere.rikkahub.revised.linkimageqa` | `2.4.8-revised.9-20260923-214954-debug` | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |

两包 versionCode 均为 185。常规 QA 签名 SHA-256 为 `31b770dc8e50adefe30a1a0252175ce59e27c142e8ed286bf693ebc80b79be21`，与工作区旧 QA 一致；独立包使用已有 Debug 签名 `743e0f9a2f9847aa5311c3189a7a80763f0eab0fb218c825f7013101fc934424`。未读取手机上已安装包的证书，不能据此替代设备端更新验证。

ARM64 文件 SHA-256：

- 常规 QA：`33FC71CDF1FF4A9BADF8AC176B31DEBB6699DF2E13AD546DB05147E9831B3C9A`
- 独立测试包：`8F67825D33200670AD7944F5CD8076AAA1ABE9E68C42E0DF868EA7403313BF52`

构建使用现有 `:app:assembleQa` 及 `:app:assembleDebug`；独立 Debug 包名通过已有 `app/build/link-image-qa.init.gradle` 临时覆盖，不修改正式构建的包名。相应目录也包含 universal 和 x86_64 APK。

本轮不安装、不卸载、不清除手机应用数据，不执行付费模型请求。APK 构建及本地测试通过，不等于该供应商/中转线路已经在线实测通过。

## 复测

1. 选择与现有安装相同包名的测试包升级，保留原模型、供应商和自定义参数。
2. 开启 Gemini 内置网络搜索，同时保留 `ask_user` / 生图 / 会话能力查询等函数工具。
3. 请求联网查询，随后继续调用一个客户端工具，确认回复及后续轮次正常。
4. 若仍报同一错误，提供该次请求的脱敏诊断：供应商类型、模型、请求通道、`tools.functionDeclarations`、`tools.googleSearch`、`toolConfig.includeServerSideToolInvocations`、`toolConfig.functionCallingConfig.mode` 及错误状态。不要提供 API 密钥或整段聊天历史。

如果诊断显示混用标志已为 true，但服务端仍要求开启，需要继续排查所用中转/兼容接口是否保留该字段，不能通过反复重发同一请求判断修复成功。
