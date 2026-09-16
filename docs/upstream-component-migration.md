# 上游组件迁移台账

> 建立日期：2026-09-16。目标分支：`codex/upstream-component-migration`。
> 基线：Revised `7302fc3d`（2.4.8-revised.9）；对照上游 `c8853531`。

分支结构：`codex/ui-emergency-regressions` 固定在 Android 紧急修复提交 `e6880bfc1`；本迁移分支建立在该提交之上，再按组件添加上游适配。两者均未包含原工作区其他未提交修改。

## 原则与边界

Revised 仓库与 `rikkahub/rikkahub` 上游没有共同 Git 祖先。该分支从 Revised 基线建立，按组件比对和移植，不执行整仓合并，也不覆盖当前主工作区的未提交修改。任何上游代码需要先检查与 Revised 的交互，再做 Android 构建、相关单测和目标设备验证。上游提交号仅是来源，不表示完整 cherry-pick。

保留 Revised 的高级外观、液态玻璃、背景图、图像生成、快捷建议、扩展管理、工作区、Web API 和 WebUi 数据契约。迁移 WebUi 时不能把上游的服务端配置、类型或鉴权方式直接替换为旧实现。

## 首批组件

| 组件 | 来源与适配 | 状态与验收 |
| --- | --- | --- |
| Android 聊天导航抽屉 | 上游 `7b76847f6` 清除输入焦点的行为；Revised 额外移除过渡时卸载聊天内容的条件 | 按钮可打开抽屉；输入法不会因旧焦点重新出现；保留 Revised 的背景、导航和会话处理 |
| Android 输入框 / IME | 上游 `f86d6e82a` 保持圆角及 8dp 间距；Revised 附加 IME 期间暂停实时模糊和折射，但不改变用户设置的透明度 | 已在 vivo V2301A / Android 16 实机验证圆角和间距；卡顿尚未消除，详见下方 |
| Android 抽屉设置操作 | Revised 补齐 `contentDescription` | UI tree 可读取“设置”；不改变 Tooltip 或按钮布局 |
| WebUi 模型选择器 | 上游 `3d75f83f3` 的 Tailwind 视口 `max-h-20` 约束，适配 Revised 的固定高度模型选择布局 | TypeScript 类型检查与 React Router 生产构建已通过；仍需小屏浏览器视觉回归和真实 Web 服务访问验证 |

## 实机与性能记录

- QA：实体设备 vivo V2301A，Android 16 / API 36，1260x2800，arm64-v8a QA 包。
- 修复前：左上角 Messages 按钮点击无效，边缘滑动可打开；输入栏键盘打开时底角变直并贴住键盘。
- 修复后：Messages 按钮打开抽屉；“设置”可被无障碍树识别；输入栏保留圆角和底部间距。
- 三次聊天键盘开合：修复前 222 帧中 41 帧错过期限（18.47%），P95 44 ms；修复后 213 帧中 35 帧（16.43%），P95 40 ms。两次为独立样本，不能把差值当作统计显著的性能提升；主线程卡顿仍需处理。
- 已尝试 Simpleperf CPU 采样，但设备端报告 0 samples；该文件不能用于热点归因，需以 Perfetto 或可产出样本的新一轮采样继续定位。
- 终端对照三次开合：174 帧中 6 帧错过期限（3.45%），P95 19 ms。当前设备未复现终端重排和系统栏泄漏。
- 本轮未观察到应用 Crash / ANR；尚未覆盖不同密度、折叠屏、Android 9/12/15/17 及多个 WebView 版本。
- Android 修复已通过 `:app:testDebugUnitTest --tests me.rerere.rikkahub.ui.components.ai.ChatComposerSurfaceTest` 与 `:app:assembleQa`。WebUi 已通过 `react-router typegen`、`tsc --noEmit` 和 `react-router build`；构建存在既有 sourcemap 和大 bundle 警告。没有在 Web 服务器或浏览器中模拟供应商标签滚动。

## 下一批与阻断条件

1. 先用同流程 Perfetto 定位聊天键盘开合的主线程耗时，验证不同输入栏材质（透明、模糊、液态玻璃）和背景图；不要因 16.43% 单项数据宣称性能问题已解决。
2. 分别对照上游聊天附件选择、图片裁剪、工作区终端和 WebView 生命周期；每个组件单独提交、运行对应测试，再决定是否导入。已在 Revised 实现的修复应登记为“已有”，避免重复移植。
3. Web 服务器访问纳入后续验收：本机 `127.0.0.1`、局域网 `0.0.0.0`、NSD 发现、Android 17 `ACCESS_LOCAL_NETWORK` 权限、鉴权切换、SSE 重连、WebUi 小屏模型选择和静态资源路径。必须在真实 Web 服务与浏览器中验证，不以静态审查替代运行时验证。
4. 迁移前后分别检查 Revised 的功能矩阵和数据迁移，保持当前工作区未提交修改隔离；只有经过审查、构建和设备验证的组件才纳入发布候选。
