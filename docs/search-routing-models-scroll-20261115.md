# 搜索路由、新模型参数与当前对话滑动验证

## 搜索修复

搜索能力按当前供应商的实际请求协议判断。通过 OpenAI Chat Completions 接入的 GPT、Claude、Gemini，即使模型保存了旧的内置 Search 标记，也会使用客户端 `search_web`；该标记不再压掉外接搜索函数。旧配置未保存 TOOL 能力时，按模型名称／ID 的注册能力补齐请求所需的函数调用能力，保留原始 model ID。

Google 原生协议使用助手 Gemini 搜索配置；发送请求时同步真实开关，能力查询同时报告 native、external、URL Context。Gemini 2 的原生工具和客户端函数冲突时，当前请求优先选中的外接搜索并暂时关闭 URL Context，助手的持久偏好不变。Gemini 3／4 允许组合。原生工具专用请求不再声明实际未发送的客户端能力。

搜索选择器的服务图标、选中态和关闭项同步显示有效状态。系统提示和 `get_session_capabilities` 使用当前请求实际声明的搜索能力，避免沿用历史消息中“没有联网工具”的结论。只有取得工具结果才能引用，不替换模型回复，不伪造搜索结果。第三方中转本身仍须支持所选协议的函数调用，不能由客户端凭空补出供应商未提供的能力。

## 官方文档核对与模型适配

通过 AnySearch 检索并读取官方文档，本次新增范围：

| 供应商 | 模型 | 能力／参数 |
| --- | --- | --- |
| MiniMax | M3.1 Flash | 1M 上下文、图像输入、工具调用；low／medium／high／xhigh／max，常开思考；Chat 使用 reasoning_effort，Anthropic 使用 adaptive 与 output_config.effort |
| 智谱 | GLM 5.3、5.3 Flash、5.3 FlashX、5.3 FlashX Legal | 1M 上下文、工具调用、low／high／max；thinking 必须 enabled；旗舰为文本输入，Flash／FlashX 支持图像 |
| Kimi | K3、K3.1 Pro、K3.1 Pro Highspeed | 1M 上下文、工具调用、low／high／max，常开思考；移除 K2 的 thinking 配置与非法采样参数 |
| Kimi | K2.7 Code、K2.7 Code Highspeed | 262144 上下文、图像输入、工具调用；常开思考与思考内容保留，移除关闭思考／非法采样覆盖，不开放不支持的 effort |

GLM Legal 使用官方文档的完整 ID `glm-5.3-flashx-legal`，不猜测裸 `glm-legal` 的服务契约。显示名称匹配用于参数与能力识别，不改写用户填写的请求 ID；厂商名称有边界约束，避免将 `grok3` 等名称后缀误识别为裸 K3。未根据未经确认的原生协议猜测 GLM／Kimi 的 Anthropic effort 格式；Responses 仅规范已有标准 effort 字段，不强制向所有中转注入新的厂商参数。旧 OFF 配置在常开思考模型上转为合法最低档位。MiniMax M3.1 的不支持字段 service_tier、reasoning_split 覆盖会移除。

官方来源：

- MiniMax：[文本生成](https://platform.minimax.io/docs/guides/text-generation)、[OpenAI 兼容协议](https://platform.minimax.io/docs/api-reference/text-openai-api)、[Anthropic 兼容协议](https://platform.minimax.io/docs/api-reference/text-anthropic-api)。
- 智谱：[GLM 5.3](https://docs.z.ai/guides/llm/glm-5.3)、[Flash／FlashX／Legal](https://docs.z.ai/guides/vlm/glm-5.3-flash)、[思考参数](https://docs.z.ai/guides/capabilities/thinking)。
- Kimi：[K3.1 Pro](https://platform.kimi.ai/docs/guide/kimi-k3-1-pro-quickstart)、[参数比较](https://platform.kimi.ai/docs/api/models-overview)、[推理档位](https://platform.kimi.ai/docs/guide/use-reasoning-effort)、[官方 K2.7 Code 模型卡](https://huggingface.co/moonshotai/Kimi-K2.7-Code)。
- Google：[工具组合](https://aistudio.google.com/docs/tool-combination)。

验证覆盖实际协议请求 JSON、模型名称推断、原始 ID 保留、无效参数清理、搜索路由、Gemini 4 的原生／URL／函数组合及能力查询。未执行收费供应商实网生成或搜索，不将本地契约验证等同于所有中转站验证。

## 滑动基线与修复

USB 真机 vivo V2301A、Android 16、QA variant，以用户当前对话为准，不发送消息。每轮包括 16 次 120 ms 上下往返、12 次向旧消息方向滑动、12 次向新消息方向滑动。工件位于 `build/reports/search-models-20261115/`，Python 驱动为 `scripts/qa_chat_scroll.py`。使用 Android performance 技能流程采集 gfxinfo、Perfetto、meminfo、页面快照与退出记录。

基线 `scroll-before/`：快速往返 98 帧／54 卡顿帧，P95 93 ms、P99 101 ms；向旧消息 253／26，P95 25 ms、P99 69 ms；向新消息 251／26，P95 25 ms、P99 65 ms。PID 21520 保持不变。

Perfetto 显示列表 idle prefetch 最长 192.627 ms，其中组合约 114 ms、测量约 54 ms，最长主线程 doFrame 80.671 ms。较小的提前范围使长消息临近可见时才开始紧急预取。实验中关闭预取后，进入长消息的组合／测量直接落在可见帧，出现 648.328 ms 的长帧，因此最终保留预取，将 aheadFraction 从 0.25 调整为 1.5，让可暂停组合更早利用空闲预算；保留 behindFraction=2.5 与后台 Markdown 解析预热。

连续手势结束后，背景／玻璃的 busy 渲染配置延迟 400 ms 恢复；下一次滑动立即进入 busy。手势间的短暂停顿不再反复更新整段正文读取的背景 CompositionLocal 和玻璃效果，列表本身的触控、滚动及停止逻辑不延迟。

## 构建与安装

最终离线构建日志：`build/reports/qa-build/20261115-023824/attempt-1.log`，`BUILD SUCCESSFUL`。应用 1196 项、AI 模块 488 项 JVM 测试通过，失败／错误／跳过均为 0，合计 1684 项。`git diff --check` 通过。

QA 版本 `2.4.8-revised.9-20261115-023835`，USB `adb install -r` 覆盖安装成功；首次安装时间保留为 **2026-10-17 22:49:10**，未卸载／清数据。APK：`app/build/outputs/apk/qa/app-arm64-v8a-qa.apk`，设备包信息保存于 `build/reports/search-models-20261115/final-package.txt`。

安装后使用原对话 ID 启动，并恢复到基线相同的消息段。最终 `scroll-installed/initial.xml` 中对照段落的 bounds 与基线一致，为 `[70,288][1190,683]`；新会话空白页、起点不同的实验轮次未作为最终对比数据。测试没有发送用户消息，未修改对话、助手或搜索配置。

最终同一起点的真机结果：

| 阶段 | 基线帧数／卡顿帧 | 最终帧数／卡顿帧 | 基线 P95／P99 | 最终 P95／P99 |
| --- | ---: | ---: | ---: | ---: |
| 16 次快速往返 | 98／54（55.10%） | 256／57（22.27%） | 93／101 ms | 46／109 ms |
| 12 次向旧消息 | 253／26（10.28%） | 214／27（12.62%） | 25／69 ms | 38／150 ms |
| 12 次向新消息 | 251／26（10.36%） | 248／26（10.48%） | 25／65 ms | 25／89 ms |

最终工件：`scroll-installed/`。40 次手势前后 PID 16274 保持不变，退出记录完全一致，当前进程 crash buffer 为空；正式聊天页和输入框仍可见。PSS 从 614860 KB 到 577240 KB，本轮没有做新的低内存压力测试，不能据此排除长期泄漏。临时保持亮屏设置恢复为原值 0，自动锁屏时长仍为 600000 ms；核验结果为 `final-validation.json`、`final-ui.xml` 与 `final-ui.png`。

最终 Perfetto 最长主线程 doFrame 80.925 ms、超过 50 ms 的帧 1 个；基线为 80.671 ms、3 个。本段已访问消息的最终轨迹没有 idle prefetch 作业，不再出现实验中进入长消息时的 648 ms 帧。快速往返的主要分布改善，但单向滑动 P99 仍有抖动，不能宣称所有消息首入、所有渲染器都已流畅。

这是同一真机、同一对话和相同起始可见段的一轮最终测量，准备过程已访问周边内容。帧数、JIT、缓存热度、后台调度及 UIAutomator 的语义快照开销会影响数据，不把该对比解释为严格隔离变量的性能提升比例。`scroll-final/` 是相同 UI 代码、起点高约一屏的前一轮验证（快速往返 P95 25 ms），保留原始证据但不替代上述最终结果。
