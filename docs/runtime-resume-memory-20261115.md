# 后台恢复、卡顿与内存排查

设备：USB ADB 连接 vivo V2301A，Android 16。应用包名 `me.rerere.rikkahub`。本次不修改 desktop / desktopApp。

## 已确认的退出原因

系统 `ApplicationExitInfo` 与 DropBox 的 `data_app_anr` 记录确认：

- 2026-11-13 22:48:22，应用在后台被低内存回收，PSS 约 734 MB。
- 22:49:07，恢复进入后因输入事件等待 5000 毫秒触发 ANR，与用户截图时间吻合。主线程在 Compose 布局节点挂接和 Lookahead 测量链中。
- 同日 18:28:12 的另一条 ANR，主线程位于 `LinkText → withReadableSpans → contrastingTextColor → wcagRelativeLuminance`，正在为富文本反复计算对比度。堆栈还包含表格单元格的组合与测量。
- 设备另有多条后台低内存回收和 ROM 清理记录；不能将这些记录等同于 Java 异常崩溃。

证据：`build/reports/runtime-20261115/before/exit-info-before.txt` 和 `historical/app-main-stacks.txt`。ROM 的 `traceInputStream` 未向应用返回堆栈，ADB DropBox 可以读取；本轮 Simpleperf 采样为 0，未将其用作 CPU 热点证据。

## 修改

- 为富文本对比度结果增加有界缓存，同一背景的亮度计算复用；HCT 选色中的每个候选色不再为所有背景样本重复执行 sRGB 幂运算。保留原有对比度、色相与透明度规则。
- 去除应用根部的共享元素 Lookahead 布局，避免所有聊天和表格都承担助手头像动画的额外前瞻测量。普通页面转场保留，助手头像不再依赖全局共享动画。
- 设置反序列化、能力归一化及序列化写入移出主线程。启动次数更新复用已加载设置并在锁内修改，避免重复解析及旧快照覆盖。
- 首屏等待实际设置和有界时间内的配色准备，不先组合 `Settings.dummy()` 的整套聊天界面；现有 StateFlow 订阅直接使用当前值。
- 背景取色增加进程内复用及小型磁盘缓存，最多 8 项；本地来源、修改时间、文件大小、画面比例共同确定缓存键。损坏缓存回退重新分析；不把远程 URL 当作永久不变内容。背景首次显示关闭淡入。
- Coil 加载器由 Application 创建，固定图片内存缓存为 32 MiB，不再在 Activity 组合时注册持有 Activity 的工厂闭包。
- 下次启动导入系统 ANR、原生崩溃、低内存和 ROM 内存清理信息到应用日志。普通强制停止、更新不作为崩溃。保留最多三份有界系统堆栈；ROM 未提供堆栈时明确记录。
- 状态 Flow 的致命错误经正常异常处理器记录，替代绕过所有日志的 `Runtime.halt()`；正常协程取消不再按致命错误处理。

## 验证

最终构建：`build/reports/qa-build/20261115-011039/attempt-1.log`，1191 项应用 JVM 测试通过，失败、错误、跳过均为 0；QA 构建与 `git diff --check` 通过。覆盖了对比度回归、取色缓存跨进程复用、来源版本隔离、损坏文件回退及退出原因分类。

相同对话的三轮抽屉 → 设置 → 返回及一次后台恢复，普通压力条件：

| 指标 | 修改前 | 最终版本 |
| --- | ---: | ---: |
| Perfetto 采样中的最长主线程 doFrame | 249.032 ms | 44.738 ms |
| 主线程超过 50 ms 的帧 | 9 | 0 |
| gfxinfo P95 | 77 ms | 65 ms |
| gfxinfo P99 | 350 ms | 250 ms |
| gfxinfo 帧数 / 卡顿帧 | 275 / 26 | 231 / 27 |
| 初次内存快照 PSS | 534963 KB | 440932 KB |

工件位于 `before/` 和 `final/`。帧数量、设备调度和 JIT 状态有差异；卡顿比例没有下降，不能据此宣称整个应用已完全流畅。Perfetto 使用本次 PID 解析 ROM 未命名的进程，属于轻量系统跟踪。Python 的页面等待包含 ADB 和 UIAutomator 开销，未把它当作应用响应时间。

截图对应的长对话经过真实低内存回收后重新打开，另通过 `am kill` 验证后台进程重建。首个可见截图和两秒后的头部控件 RGB 均为 `(242, 246, 255)`，输入框在屏幕内。五次完整冷启动为 2799 / 2957 / 2884 / 3364 / 2978 ms，中位数 2957 ms。该测试验证实际设置与配色就绪的首屏；没有将它与原来先显示默认色的首帧直接比较来宣称启动加速。证据：`recovery/results.json`、对应截图和启动输出。

## 内存压力与对象检查

使用 `scripts/runtime_memory_pressure.c` 和 Python 驱动，其他后台应用保留。工具逐步申请内存，目标系统 `MemAvailable ≤ 1536 MiB`，最多申请 4096 MiB，150 秒自动退出，512 MiB 保护下限，`oom_score_adj=1000`。未修改系统低内存策略、禁用其他应用或添加常驻服务。

第一轮最低可用内存 1214828 KB，六轮导航及后台恢复完成；PSS 在数轮后趋稳，回到后台下降。后续一轮手机前台切到 QQ 邮箱，QA 被系统以 `LOW_MEMORY` 回收（后台重要性 400），该轮未记为完整前台测试通过；其后的恢复已单独验证。

用户确认保持 QA 前台后完成最终测试：最低可用内存 **1135064 KB，约 1.08 GiB**；测试工具最多申请 3840 MiB 后正常退出。六轮抽屉 / 设置 / 返回，加两轮实际菜单与标题弹窗打开后取消、一次后台恢复全部完成。进程 PID 14381 保持不变，系统没有新增 ANR 或退出记录。PSS 从首轮 504082 KB 到末轮 490017 KB，后台为 287797 KB；没有观察到随每轮导航持续增长。gfxinfo 共 612 帧、77 卡顿帧，P95 101 ms、P99 550 ms，说明在压力下仍有渲染停顿，未声称完全消除卡顿。结果：`pressure-confirmed/pressure-summary.json`、`pressure-confirmed/flow/summary.json`。

GC 后 HPROF 使用 `scripts/hprof_histogram.py` 检查对象计数。该脚本输出实例数据大小和直方图，不计算引用链或 retained size，不能作为完整泄漏分析器。前后快照中 RouteActivity、RikkaHubApp、Coil RealImageLoader 均为 **1 个**，未见这几类对象随导航累积。两个快照展示不同对话，Bitmap 数由 11 到 40，不把总对象数或 Bitmap 差值当作泄漏或优化比例。工件为 `before-pressure.hprof`、`after-pressure.hprof` 及相应直方图。上述样本不能排除所有长期泄漏。

压力进程已退出，测试可执行文件与设备上的 HPROF 临时文件已移除；手机自动锁屏时长恢复为原值 600000 毫秒。未触及其他应用的数据、设置或后台进程。

## 安装

QA 版本 `2.4.8-revised.9-20261115-011051`，通过 USB `adb install -r` 覆盖。首次安装时间仍为 **2026-10-17 22:49:10**，未卸载或清理应用数据，正式聊天界面与输入框正常显示。核验记录：`final-package.txt`、`final-ui.xml`、`final-ui.png`。
