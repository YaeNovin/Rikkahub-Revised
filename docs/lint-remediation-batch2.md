# Lint 第二批修复

日期：2026-09-15。当前为未发布修改，延续第一批修复，不调整版本号。

## 范围

对应 `.9` 原报告的 6 类、55 条记录：36 条资源读取、5 条不可观察 Locale、2 条静态 Locale、
5 条屏幕尺寸、6 条触摸可访问性、1 条隐藏数据库 API。缺失翻译和资源清理继续属于后续批次。

## 语言与资源

- 新增 `rememberAppResources`，把 Compose 的当前资源保存在可更新状态中；异步保存、导出、备份、导入和聊天建议操作完成时读取最新资源，不因语言更新重启操作。
- 日志卡片、详情、统计日期、聊天建议时间和语言名称随当前应用配置更新；日期格式器按语言和格式缓存，移除全局固定 Locale。
- 提示词预览使用当前应用语言，并保留已编辑的测试输入；未编辑的默认示例才随语言更新。
- 数值协议、文件名等技术格式不作语言替换，不改已有请求结果和导出记录。

## 窗口约束

`windowBoundedHeight` 在测量阶段与父布局实际剩余空间取交集，用于外观弹窗、灵感设置和聊天建议面板。
键盘与系统内边距由 Dialog/Modal 宿主处理，不再次手工扣除同一内边距。
弹窗在父页面捕获窗口边界，避免用自身包裹内容的窗口尺寸反复缩小。
富内容预览读取当前窗口宽度，保持原有比例和高度上下限。
聊天建议继续使用单一滚动容器，原背景和表面样式保留。

## 触摸与可访问性

- WebView 的滚动协调移入事件分发，仅调整父列表拦截，随后交给原生 WebView 分发；不合成第二次点击，也不覆盖网页自身的链接和无障碍行为。
- 终端移除两个笼统的 ACTION_UP 监听，避免拖动/选中文本后无条件弹出键盘。
- 终端普通单击先按原逻辑处理链接，否则调用标准 `performClick`，与无障碍 ACTION_CLICK 共用键盘激活回调；上游终端是 final 类，使用其公开点击接口。

## 数据库窗口

已核对当前依赖二进制：Requery `CursorWindow(String)` 默认大小是 2 MiB，另有公开的 `CursorWindow(String, int)` 及 `setWindow`。
原 `DatabaseUtil` 修改的是系统 `android.database.CursorWindow` 的私有静态字段，不能配置应用实际使用的 Requery 游标。

移除启动时的反射调用及工具类，新增 `LargeRowOpenHelperFactory` 包装原 Requery 工厂。
四种 SupportSQLite 查询入口在返回游标、开始读取之前通过公开 API 设置 32 MiB 窗口。
每个游标拥有并负责关闭自己的窗口，分配失败会关闭游标并传播错误；不通过失败后重试 SQL 扩大窗口。
窗口是每个活动游标的容量上限，并非无限制读取；超大行和多游标并发仍需遵守内存预算。

包装保留原工厂配置、WAL、事务、绑定参数、取消信号与数据库回调；创建、升级、打开等回调也使用同一包装数据库。
数据库名称、表结构、迁移和已有数据格式不变。

## 验证与复现

```bash
./gradlew :app:testDebugUnitTest --tests '*WindowContentBoundsTest' --tests '*WebViewGestureTest' --tests '*InspirationEditorStateTest' :app:compileQaKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:lintRelease --init-script scripts/lint-batch1.init.gradle --init-script scripts/lint-batch2.init.gradle --no-configuration-cache
```

Windows 使用 `gradlew.bat`。两份 init 脚本合用时同时检查前两批，共 13 类；第二份脚本单独使用时仅检查第二批 6 类。
默认完整 Lint 保持不变，定向检查不代表全项目已清零。

设备回归代码：

- `AppResourcesRegressionTest`：更换配置后，既有异步完成回调读取新语言，日期格式器更新，任务启动次数仍为一次。
- `LargeRowDatabaseTest`：读取超过 2 MiB 的多字节文本，覆盖四种查询入口、打开回调和关闭后重开，核对窗口容量、内容与数据库身份。

当前 ADB 无设备，设备用例的编译与实际运行需区分记录。尤其大文本实际读取、TalkBack、网页单次点击、终端链接/手势和键盘/分屏体验仍需实机验证。

## 本轮验证结果（2026-09-15）

- `:app:testDebugUnitTest` 定向运行 11 项：`WindowContentBoundsTest` 4 项、`WebViewGestureTest` 5 项、`InspirationEditorStateTest` 2 项，全部通过（0 失败、0 错误）。
- `:app:compileQaKotlin`、`:app:compileDebugAndroidTestKotlin` 和 `:app:compileReleaseKotlin` 均通过。
- 联合 init 脚本检查前两批共 13 类 Lint；第二批 6 类（55 条原始记录）当前报告为 0 条，`lint-batch2.txt` 为 `No issues found.`，XML 为空 issues 集合。
- 本轮未修改版本号、未发布、未推送，也未执行设备安装。当前 ADB 无可用设备；新增的两个 Android instrumentation 测试仅完成编译，尚未在真机/模拟器运行。
- 构建日志仍包含既有 Kotlin/Compose 弃用和 opt-in 警告，它们不属于本批 6 类 Lint 检查，保留到后续专项批次。
