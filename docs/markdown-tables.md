# Markdown 原生表格增强

开发源码更新：2026-11-19。沿用现有 Compose 表格与 Markdown 解析器，不引入 WebView 表格或额外渲染依赖。

## 内容与布局

支持 GFM 表头分隔行中的左、中、右对齐，表头与正文共用列宽：

```markdown
| 左对齐 | 居中 | 右对齐 |
| :--- | :---: | ---: |
| 文本 | 文本 | 12345 |
```

原生与 HTML 转 Compose 路径共用 `DataTable`，表头使用较高字重，交替行使用较淡底色。沿用消息字体、字号与前景可读性计算，兼容深浅色、背景与文字取色。单元格里的链接、引用、加粗、斜体、行内代码、HEX 色块与引号高亮继续由现有渲染器处理。

Markdown 表格列宽在 80–240 dp 范围内按内容确定，并尽量填满视口；长内容换行，宽表横向滑动。溢出时底部显示滚动位置条，横向滚动不替代聊天的纵向滚动。列宽拉伸尊重各列最大值，避免为了填满宽屏而突破换行限制。

短行补齐空单元格，超出表头列数的内容按 GFM 规则忽略。使用 `\|` 表示单元格内的管道符。保留原生表格卡片已有的 Markdown 复制和 CSV 导出，不修改原始消息。

## 实现与验证入口

- `MarkdownTables.kt`：从解析后的表格读取单元格、分隔行和列对齐。
- `DataTable.kt` 与 `TableColumnWidths.kt`：两阶段安全测量、列宽分配、等高行、对齐、主题与滚动提示。
- `MarkdownTableTest`：GFM 对齐、嵌套表格、短行/超宽行、转义管道、流式不完整行与列宽边界。
- `TableRegressionActivity`：独立 Debug 样本，使用随机上下文；不修改真实对话、供应商或设置。

```text
python scripts/build_qa.py :app:assembleDebug :app:testDebugUnitTest --tests '*MarkdownTableTest' --performance-probe --offline --max-attempts 1
python scripts/qa_markdown_tables.py --serial SERIAL --output build/reports/markdown-tables-device
```

先安装对应的独立 Debug APK。设备脚本核对版本，并检查两种渲染路径、深浅主题、130% 字号、左右滑动、精确引用回调、长内容不覆盖下一行、流式完成与进程稳定性。原始结果、截图和 UI 树仅保留在忽略的构建报告目录。

语法依据：[GitHub 官方表格说明](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/organizing-information-with-tables)、[GFM 规范](https://github.github.com/gfm/#tables-extension-)。合并单元格、多行块级内容和表格排序不属于 GFM 标准语法，本轮没有增加这些扩展。

## 本轮验证结果

最终 Debug 构建与测试成功：`build/reports/qa-build/20261119-113901/attempt-1.log`。应用 JVM 测试 1,251 项、Material3 合成图像测试 2 项全部通过，无失败或跳过；其中新增表格用例 8 项。上传清单工具的 4 项 Python 测试也通过。

USB 设备为 vivo V2301A、Android 16。独立 Debug 版本 `2.4.8-revised.9-20261119-113917-debug`，使用合成表格和随机上下文：

| 渲染路径 | 主题 | 字号 | 对齐、换行、横向滑动、引用、流式与滑动稳定性 |
| --- | --- | --- | --- |
| 原生 | 深色 | 100% | 通过 |
| 原生 | 浅色 | 100% | 通过 |
| HTML 转 Compose | 浅色 | 100% | 通过 |
| HTML 转 Compose | 深色 | 100% | 通过 |
| 原生 | 深色 | 130% | 通过 |
| HTML 转 Compose | 深色 | 130% | 通过 |

另重跑 `qa_markdown_citations.py` 的原生/HTML × 深浅色四组回归，全部通过。每组验证精确引用回调、长按、表格引用、流式补全、代码字面量和连续滑动。没有调用真实模型或搜索供应商。

大字号下表格可能超过屏幕，验证时先横向定位目标列、必要时纵向定位长行底部；流式完成后允许保留横向滚动位置。最终设备结果位于 `build/reports/markdown-tables-20261119/device-final/device-results.json`，引用回归位于同级 `citation-regression/`。截图与 UI 树仅保留本机。滑动期间进程稳定，系统退出记录只见测试主动重启；设备返回的进程 logcat 为空，不能用空日志单独证明没有异常。本轮不提供帧率或内存压力结论。

QA 构建日志：`build/reports/qa-build/20261119-115501/attempt-1.log`，`BUILD SUCCESSFUL in 2m 47s`。已通过 `adb install -r` 覆盖 `me.rerere.rikkahub`：

- 安装版本：`2.4.8-revised.9-20261119-115514`。
- 首次安装时间保持为 `2026-10-17 22:49:10`；没有卸载或清除数据。
- 已启动并确认进程存活，未捕获到该进程的 FATAL EXCEPTION。
- 充电保持亮屏设置恢复为原值 `0`，并读回确认。
- ARM64 APK SHA-256：`f526d306488531b83aeaf9ae3b8d39742fa60d9ca4c2bd999058f223c71f72eb`。

安装详情记录在本地 `build/reports/markdown-tables-20261119/qa-install-audit.json`。本轮没有发布 GitHub 源码或安装包。

## 本轮验证结果

最终 Debug 构建与测试成功：`build/reports/qa-build/20261119-113901/attempt-1.log`。应用 JVM 测试 1,251 项、Material3 合成图像测试 2 项全部通过，无失败或跳过；其中新增表格用例 8 项。上传清单工具的 4 项 Python 测试也通过。

USB 设备为 vivo V2301A、Android 16。独立 Debug 版本 `2.4.8-revised.9-20261119-113917-debug`，使用合成表格和随机上下文：

| 渲染路径 | 主题 | 字号 | 对齐、换行、横向滑动、引用、流式与滑动稳定性 |
| --- | --- | --- | --- |
| 原生 | 深色 | 100% | 通过 |
| 原生 | 浅色 | 100% | 通过 |
| HTML 转 Compose | 浅色 | 100% | 通过 |
| HTML 转 Compose | 深色 | 100% | 通过 |
| 原生 | 深色 | 130% | 通过 |
| HTML 转 Compose | 深色 | 130% | 通过 |

另重跑 `qa_markdown_citations.py` 的原生/HTML × 深浅色四组回归，全部通过。每组验证精确引用回调、长按、表格引用、流式补全、代码字面量和连续滑动。没有调用真实模型或搜索供应商。

大字号下表格可能超过屏幕，验证时先横向定位目标列、必要时纵向定位长行底部；流式完成后允许保留横向滚动位置。最终设备结果位于 `build/reports/markdown-tables-20261119/device-final/device-results.json`，引用回归位于同级 `citation-regression/`。截图与 UI 树仅保留本机。滑动期间进程稳定，系统退出记录只见测试主动重启；设备返回的进程 logcat 为空，不能用空日志单独证明没有异常。本轮不提供帧率或内存压力结论。

QA 构建日志：`build/reports/qa-build/20261119-115501/attempt-1.log`，`BUILD SUCCESSFUL in 2m 47s`。已通过 `adb install -r` 覆盖 `me.rerere.rikkahub`：

- 安装版本：`2.4.8-revised.9-20261119-115514`。
- 首次安装时间保持为 `2026-10-17 22:49:10`；没有卸载或清除数据。
- 已启动并确认进程存活，未捕获到该进程的 FATAL EXCEPTION。
- 充电保持亮屏设置恢复为原值 `0`，并读回确认。
- ARM64 APK SHA-256：`f526d306488531b83aeaf9ae3b8d39742fa60d9ca4c2bd999058f223c71f72eb`。

安装详情记录在本地 `build/reports/markdown-tables-20261119/qa-install-audit.json`。本轮没有发布 GitHub 源码或安装包。
