# 图像 API、决策模型与提示词修复

## 图像供应商协议

日志中的 `api.lk888.ai` 对 `tt-image-2.6` 拒绝 `/v1/images/*`。其文档规定使用独立媒体协议，不能只更换 URL：

- 创建：`POST /v1/media/generate`，请求为 `{model, prompt, params}`，读取 `data.task_id`。
- 查询：`GET /v1/skills/task-status?task_id=...`，间隔 5 秒；只根据 `state` 和 `is_final` 判断结果，不匹配可翻译的状态文字。
- 成功后读取 `result_urls`（兼容 `result_url`），再下载图片；终态失败立即停止。创建请求不自动重试，等待超时提示任务号，避免重复创建付费任务。
- 参考图通过 `params.images` 数组传递，支持内联 JPEG、PNG、WebP、GIF，每张最多 10MB、合计最多 30MB。
- 接口每次固定创建一个任务，图片数量限制为 1；不向该协议发送 OpenAI 的 `n`。后续参数统一更新见 [优客 GPT Image 2.6 参数与版本](lingke-image26-shared-parameters.md)，审核、压缩等模型参数现与原生构造器共享并封装在 `params` 内。

仅对已知供应商的对应图像模型自动启用该协议。其他供应商继续使用既有生成和编辑端点。模型高级设置新增独立的生成、编辑相对路径，可以明确指定 `/images/generations`、`/images/edits`，或按供应商要求使用自定义路径。`/media/generate` 代表上述媒体协议。路径保留供应商域名和基础路径。

文档来源：

- [供应商 TT Image 2.6 文档](https://yk.lk666.ai/apidoc#model/tt-image-2.6)
- 该页面公开数据接口：`https://api.lingkeai.ai/gongkai/apidoc/moxing-xiangqing?mingcheng=tt-image-2.6&domain=yk.lk666.ai`
- 通用接口定义：`https://api.lingkeai.ai/gongkai/apidoc/tongyong-jiekou?domain=yk.lk666.ai`

## 独立决策模型

增加 `DECISION` 模型类型。自动发现、新增、按名称识别，以及旧配置中的 `decision-model-preview` 都能识别为决策类型；实际请求仍发送配置中的模型 ID。

“默认模型与提示词”新增默认决策模型与评估提示词。聊天建议设置支持单独选择决策模型、跟随默认决策模型或关闭评估。建议先由聊天模型生成，再使用 System One 的 `score` 问题评分并排序。评分异常保留原建议，不把决策模型作为文字生成接口调用。

[阿里云官方协议](https://help.aliyun.com/en/model-studio/decision-model-api)使用 `POST /compatible-mode/v1/systemone`，支持分类、是非判断、评分，不生成聊天文本。本次使用 4 级评分，校验返回分数在 0–3 之间；自定义评估标准与对话状态分别传递。

## Android 提示词崩溃

原变量检查正则末尾使用未转义的 `}`，在 Android ICU 中触发 `PatternSyntaxException`。变量检查和替换现共用完整转义的表达式，保留单花括号、双花括号、空格和大小写兼容，替换使用函数结果，反斜杠和 `$1` 等文本按字面保留。

真机同时发现较长提示词及变量说明会把弹窗操作按钮挤出屏幕；编辑器内容增加滚动，保存、取消及恢复默认操作均可到达。

回归包括 JVM 协议用例及隔离 Debug 应用中的真实提示词编辑器。Python 真机脚本：

```text
python scripts/qa_model_api_fixes.py --adb "<local-path> tools/adb.exe" --serial 192.168.1.227:41617
```

该脚本仅使用无线 ADB，打开并取消六个真实提示词编辑器，保存 UI 树、截图和 Android ICU 检查结果。隔离 Debug Activity 不包含在 QA 包中。

供应商协议验证使用本地 HTTP 响应夹具；未使用用户密钥发起付费生图或决策调用。

## 本轮验证

- AI 模块：466 项 JVM 测试通过。
- App 模块：1176 项 JVM 测试通过。
- 新增回归覆盖完整媒体创建、状态轮询、结果下载、请求参数结构、路径覆盖、旧路径保留、终态失败、取消，以及决策模型类型迁移和提示词转义。
- 无线真机：vivo V2301A / Android 16，Android ICU 检查通过；翻译、标题、聊天建议、OCR、上下文压缩、决策评估六个真实提示词编辑器均可打开并滚动到取消、保存操作。
- 真机结果与截图：`build/reports/model-api-fixes/device-final/result.json`、`editor-0.png` 至 `editor-5.png`。
- 最终 QA 版本：`2.4.8-revised.9-20261109-022734`；安装方式为覆盖安装，原包首次安装时间为 `2026-10-17 22:49:10`。
- 最终 QA 中已实际打开默认决策模型选择器，确认已有 `decision-model-preview` 显示为“决策”类型；未修改用户的模型选择或提示词。临时自动锁屏时间已恢复原值 600000 毫秒。
- 完整验证摘要与安装包 SHA256：`build/reports/model-api-fixes/verification.json`。
