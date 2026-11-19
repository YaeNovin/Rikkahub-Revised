# Lint 第三批修复

日期：2026-09-15。本批先处理可以明确判定、影响面小且可直接验证的资源维护问题，不调整版本号。

## 修改

- `EmptySuperCall`：移除 `ChatVM.onCleared` 对空实现 `super.onCleared()` 的调用，保留会话任务取消和引用清理。
- `UnnecessaryArrayInit`：将表格列宽数组改为默认零初始化，保持数组长度和值不变。
- `Typos`：修正供应商页面文案 `Avaliable Models` 为 `Available Models`；Lint 对 `us-central1` 的提示属于有效技术区域标识，保留不改。
- 未使用资源：删除确认是 Android Studio 模板遗留且无引用的五个紫色/青色颜色和 `white`，以及无引用的 `patreon` 向量；业务使用的字符串暂不删除，避免动态资源名或跨模块引用被误清理。新鲜定向 Lint 仍报告需逐项审查的字符串资源，未进行批量删除。
- 图标目录：将自适应图标 XML 从 `mipmap-anydpi-v26` 合并到 `mipmap-anydpi`（项目最低 API 已为 26），并移除空的旧目录；位图资源移至 `drawable-nodpi`，保持资源 ID 不变，避免密度缩放造成占位图和供应商图标变形。通过 `scripts/generate-monochrome-icons.py` 从前景图 alpha 轮廓生成独立白色单色图层，保留透明度供启动器着色；新鲜图标与目录检查已通过。
- 数量文案与翻译：已完成候选、调用点和各语言资源的盘点，剩余复数候选需按调用处改用 `pluralStringResource` 并逐语种补充；缺失翻译数量较大，后续按页面分批翻译，未使用资源清理优先于翻译，避免对将删除的键重复处理。
- 缺失翻译审计：新增 `scripts/audit-locale-resources.ps1`，只读比较默认资源与日/韩/俄/繁中键集合并输出 TSV，不自动复制英文或覆盖已有翻译。
- 当前审计结果：首批新增翻译资源后，日语、韩语、俄语各缺 1,659 个默认字符串键，繁体中文缺 984 个；这些键将按页面分批提供人工译文，暂不以英文复制或全局抑制替代翻译。

## 验证

使用 `scripts/lint-batch3.init.gradle` 的 `:app:lintRelease` 定向检查三类问题；需与前两批联合检查时同时传入三份 init 脚本。最终报告为 0 errors、0 warnings、0 issues。JVM/编译验证沿用项目现有测试任务，未改变业务协议或数据库结构。

本批未修改资源 ID、未发布或推送。当前已将消息数和引用数迁移为复数资源；其余复数候选仍需按调用处逐项处理。新鲜定向检查剩余 197 条 `UnusedResources`，其中包含动态/跨模块候选，需逐项确认后再删除。缺失翻译仍按页面分批补齐；不会通过全局 suppress 或 baseline 掩盖未完成项。
