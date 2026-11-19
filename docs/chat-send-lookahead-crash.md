# 发送消息后的 Lookahead 布局崩溃

## 日志与定位

用户提供 `<local-path>`，记录主线程未捕获异常：

```text
IllegalStateException: LookaheadDelegate has not been measured yet when measureResult is requested.
```

堆栈中的布局关系为：`LookaheadDelegate.placeSelf` → `AnimatedEnterExitMeasurePolicy` / `EnterExitTransitionModifierNode` → `RowMeasurePolicy` → `PaddingNode` → `LazyListMeasuredItem.place`。触发时正在执行 `LazyListState.onScroll` 与 `DefaultFlingBehavior.performFling`。

这条堆栈直接指向带可见性动画的列表行。聊天消息列表的 `LoadingIndicator` 项使用 Row + padding，内部两个互斥的 `AnimatedVisibility` 分别显示普通等待图标和处理状态，布局关系与日志一致。发送消息后，OCR、上下文压缩及其他处理会更新 `processingStatus`，普通等待和处理状态可能在滚动过程中切换。

## 修复

等待状态行抽出为生产组件 `ChatGenerationStatus`，去掉两个进入/退出布局动画，使用固定 28dp 指示器区域显示现有加载图标或圆形进度条，按当前处理状态显示文字。

侧边栏会话行的生成中标记同样会在发送后变化，也符合 `AnimatedVisibility` → Row → padding → LazyList 的堆栈层级，因此将生成中及置顶标记抽出为 `ConversationStatusBadges`，按当前状态直接显示。会话列表本身的项目位移动画保留。

该日志没有应用层布局调用帧，无法仅凭堆栈在这两处状态行间进一步区分。此次修复覆盖两个与日志匹配、且会在发送后更新的路径。

固定区域在当前测量周期内完成测量，不再因等待状态切换保留退出中的 Lookahead 子节点。现有加载图标动画继续工作，处理状态文本继续更新。

修复仅影响聊天等待行及侧边栏会话状态标记，不改消息发送、流式响应、数据保存或其他页面动画；没有吞掉异常或全局关闭 Compose 动画。

## 回归方式

隔离 Debug Activity `ChatSendLayoutRegressionActivity` 使用固定消息、SharedTransitionLayout 和实际生产等待行，逐帧切换普通等待/处理状态，插入消息并滚动到底部。Debug 内保留原等待行供对照；QA 包不包含该测试入口。

Python 脚本 `scripts/qa_chat_send_layout.py` 驱动真实滑动并检查进程、结果标记和崩溃日志，分别覆盖旧/新布局和 Material/应用图标两种加载样式。该测试不读取用户对话，也不调用供应商。

仅通过 JVM 测试无法验证 Compose 测量/布局时序。真机结果、对照是否复现原异常和覆盖安装版本，应以本轮设备报告为准。

## 验证记录

应用 1177 项 JVM 测试通过，Debug 与 QA 编译成功。

首轮只检查完成日志时，有一组测试未收到标记而超时，但未检测到进程退出或原 Lookahead 崩溃。最终脚本改为检查每次运行独立的完成文件，再结合进程存活、UI 截图和崩溃缓冲验证，不依赖单条 logcat 信息。

最终真机测试使用 USB 连接的 vivo V2301A / Android 16。旧实现的两组对照未复现原偶发崩溃，因此不能把压力测试通过解释为已完整还原其触发条件。此次修复依据日志布局层级和发送后的状态变化路径，消除相关可见性动画产生的退出节点。

最终修复版本在两种加载样式下均完成 1800 次状态更新，共 3600 次状态更新、212 次消息插入及 173 次真实快速滑动。完成文件、进程存活、截图和崩溃缓冲均通过检查，没有出现原 Lookahead 异常。

QA `2.4.8-revised.9-20261112-011356` 已通过 USB 覆盖安装并冷启动成功。首次安装时间仍为 `2026-10-17 22:49:10`，自动锁屏时间已恢复原值 600000 毫秒。没有执行卸载、清数据或真实供应商调用。

完整报告与安装包 SHA256：`build/reports/chat-send-crash/verification.json`。最终真机对照报告、截图和日志：`build/reports/chat-send-crash/device-final/`。
