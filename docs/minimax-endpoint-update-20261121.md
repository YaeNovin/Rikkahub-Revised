# MiniMax 国内 API 域名更新

核对日期：2026-11-21。中文官方文档当前使用 `api.minimax.cn`，原 `platform.minimaxi.com` 文档入口读取后已显示 `platform.minimax.cn` 链接与示例。国际文档继续使用 `api.minimax.io`。

## 地址与协议

| 用途 | 应用中的 Base URL | 最终接口 |
| --- | --- | --- |
| 国内 OpenAI 兼容聊天 | `https://api.minimax.cn/v1` | `POST /v1/chat/completions` |
| 国内 Anthropic 兼容聊天 | `https://api.minimax.cn/anthropic/v1` | `POST /anthropic/v1/messages` |
| 国内语音合成 | `https://api.minimax.cn/v1` | `POST /v1/t2a_v2` |
| 国内音色列表 | 同语音配置 | `POST /v1/get_voice` |
| 国际平台 | 保留原 `https://api.minimax.io` 配置及对应协议路径 | 不自动切换到国内平台 |

官方 Anthropic SDK 示例的 Base URL 是 `/anthropic`，SDK 自行追加 `/v1/messages`；本项目的 Claude 适配器追加 `/messages`，因此应用默认值继续带 `/anthropic/v1`，不会少一层或重复 `/v1`。

## 改动

- 更新国内内置聊天供应商与新建 MiniMax TTS 的默认地址。
- 本机设置和独立 Web 设置读取时将旧 `api.minimaxi.com` 国内标准 Base URL 转为新域名，保存时写入转换后的值；从备份恢复或导入的旧地址同样经过该流程。
- 只匹配 HTTPS、默认端口、无用户信息/查询/片段的原国内域名，路径限根、`/v1`、`/anthropic`、`/anthropic/v1`（允许尾斜杠）。保留协议路径、供应商/模型 ID、API Key、参数和音色设置；自定义路径、带路由参数的地址、国际地址及第三方中转不改写。
- 补齐新域名的官方能力识别、OpenAI 兼容长度字段、Anthropic 思考请求、原有 MiniMax 视频路由和语音模型目录边界。旧域名识别继续保留，不通过字符串包含来认定官方供应商。
- 本次依据官方新示例更新入口，不宣称旧域名已停止服务，也不推测平台账户与密钥可跨地区通用。

## 连通性核对

本机对国内新旧域名及国际域名进行了合计 13 次无密钥检查，HTTP 保持证书验证。没有发送聊天文本、音频或用户凭据，没有生成模型输出。

| 检查 | 本次结果 | 能说明的范围 |
| --- | --- | --- |
| 三个 API 根路径 `GET /` | HTTP 404 | 根域名不是网站首页，浏览器 404 不能代表模型 API 不可用。 |
| 新旧国内及国际 Chat Completions | HTTP 401，缺少认证头 | DNS/TLS/路由可达，接口拒绝匿名调用。 |
| 新旧国内及国际 Anthropic Messages | HTTP 401，缺少认证头 | 同上。 |
| 新旧国内及国际 TTS | HTTP 200，但 JSON `base_resp.status_code=1004` | 业务鉴权失败，不是合成成功；现有语音解码器会检查业务状态。 |
| 新国内音色列表 | HTTP 200，业务状态 1004 | 路由可达，仍需要账户凭据。 |

这是当前电脑网络下的无鉴权可达性证据，不证明手机网络、账户余额、密钥权限或真实聊天/语音生成均成功。原始结果保存在忽略的 `build/reports/minimax-endpoint-20261121/network-probe.json`。

## 回归

Common、AI、Speech、App 四个模块的 Debug 编译及 **59 项定向 JVM 回归全部通过**：

- 公共 URL：标准路径与尾斜杠、大小写/默认端口、幂等迁移、国际与代理地址、查询/片段/非标准端口回退、伪装主机排除。
- 配置：序列化后的旧聊天/语音设置加载、API Key/ID/模型/音色/参数保持、重复转换、其他协议与自定义地址保持、新默认值。
- 协议：新旧国内及国际域名的 MiniMax 输出长度字段与思考参数、原视频路由、语音参数及业务状态解码、官方模型预设边界。

构建结果为 `BUILD SUCCESSFUL`，日志在忽略的 `build/reports/qa-build/20261121-111017/`；逐类测试汇总在 `build/reports/minimax-endpoint-20261121/test-result.json`。文档链接和换行检查通过。

本轮未使用真实 Key 进行生成、未执行手机端付费接口调用，不将无鉴权探测或 JVM 用例视为真实模型生成成功。没有发布安装包或覆盖常用 QA，也没有改动渲染实验目录。

## 官方依据

经 AnySearch 与 Tavily 交叉读取核对：

- [中文前置准备](https://platform.minimax.cn/docs/guides/quickstart-preparation)
- [中文 OpenAI SDK](https://platform.minimax.cn/docs/api-reference/text-openai-api)
- [中文 Anthropic SDK](https://platform.minimax.cn/docs/api-reference/text-anthropic-api)
- [中文同步语音合成](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)
- [国际站 OpenAI SDK](https://platform.minimax.io/docs/api-reference/text-openai-api)
