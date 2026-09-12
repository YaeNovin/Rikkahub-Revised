# 2026-09-09 LaTeX 崩溃与输入区修复

## 崩溃原因

用户日志 `rikkahub-logs-1788920709963.json` 来自 `2.4.8-revised.7-dev-20260909-024137`。
堆栈直接指向 `latexReadableFallback`：公式降级时创建的 `\\(?:left|right|,|;|!|quad|qquad)\b?` 含有量化零宽边界，Android ICU 拒绝编译并抛出 `PatternSyntaxException`。桌面 JVM 接受此表达式，因此此前单元测试通过不代表 Android 兼容。

现在将字母命令与标点命令分开匹配，字母命令用 `(?![A-Za-z])` 判断 TeX 命令结束，标点命令直接匹配。保留 `leftover` 等长名称以及箭头符号，不再用 `\b?`。普通的未知公式也会经过该降级函数，回归覆盖它们。

## 输入区

原先普通模糊传入只含背景的 `navigationHazeState`，玻璃分支也从全局背景 local 取样，导致后方消息被重新采样的壁纸盖住。现在传入包含真实聊天内容的 `chatChromeHazeState`，玻璃分支支持明确的源覆盖，普通卡片仍保持其背景源。

输入区选项集中到一个栏目，材质由已有 `enableBlurEffect` 和 `applyToComposer` 解析；切换时一次写入两项记录，强度和其他设置不变。统一不透明度允许 0%，关闭动态效果不再改为 100%，旧 Dock 数值不参与表面着色。旧字段仍保留用于读取备份和旧版本迁移。

## 验证方法

- `LatexNormalizationTest` 与 `AndroidRegexCompatibilityTest` 覆盖降级文本和 Android 正则。
- `ChatComposerSurfaceTest` 覆盖无壁纸模糊、旧开关兼容、材质切换、透明度和动态效果关闭。
- `scripts/AndroidLatexProbe.java` 可与 QA 编译出的 `LatexNormalizationKt` 类及 Kotlin 标准库一起转为 DEX，在已授权设备的 Android Runtime 执行；它先确认旧正则会被拒绝，再执行 7 项真实应用降级函数测试。无需安装或启动应用，不读取应用数据。
- 输入框与消息的实际透视、AGSL/GPU 效果需设备视觉回归，不能仅凭编译与数学测试判定。
