# 对话图片导出与数理图兼容修复

范围：Android 聊天图片导出。未修改供应商 API、Ktor/Web UI 或放开任意脚本。

## 导出行为

- 完整测量所选消息的自然高度后再绘制。过去按 800 万像素预算向 Column 施加高度上限，可能裁掉末尾；现在预算仅限制单张输出，超长结果按顺序拆为多张 PNG。
- 固定 1080 像素宽，保留用户字体缩放。单张最多 800 万像素、8192 像素边长，逐张编码保存和释放，不同时持有所有位图。各页为连续像素切片，可能在文字/图形中间分页，但相邻页无缺行或重复；不是语义分页。
- 保存后一次分享全部图片，文件名附带页码。极端长布局（超过 200000 像素）明确拒绝并要求分批选择，绝不将截尾结果称为完整导出。
- 等待 Markdown、图片、WebView 和尺寸稳定。渲染失败/超时改成可见错误占位并汇总警告，不因为一幅图错误而阻断整段对话，也不把空白当成功。全局加载超时仍报错。
- SVG、Mermaid、乐谱等自然高度图形在导出时展开；数学图的参数区、图例不再按聊天视口的滚动高度裁切。图表/地图仍导出默认视图，不展开数据缩放窗口中的所有数据。
- 源码与 Diff 展开并换行；图形导出操作按钮不写入图片。工具生成的图片也纳入导出；文件/音视频附件保留说明，不尝试将声音或视频完整栅格化。
- 仍按“展开思考”选项处理推理文字。聊天导出从消息源码重建，不能保留另一个预览内临时缩放、滑块值或播放进度；动画导出静态帧。

## 数理图

截图中的初值/步长不匹配改用连续滑块保留初值；向量/线段/圆及参数支持受限纯文字标签。`jsxgraph` 下的原生脚本显示源码，不执行，也不再当 JSON 报错。详见 [数理图说明](math-plot-and-sigil.md)。

## 回归入口

- JVM：`DeclarativeRenderSpecTest`、`GeometryPreviewTest`、`RenderingToolsTest`、`BitmapComposerTest`、`ExportRenderTrackerTest`、`RendererSourceHighlightTest`、`ChatMessageCotTest`、`WebViewAssetPolicyTest`。
- 浏览器：先运行 `GeometryPreviewTest` 生成生产 HTML，然后运行 `node scripts/verify-geometry-preview.cjs` 与 `node scripts/verify-render-export.cjs`。后者检查实际滑块初值、标签作为文本、长 SVG 末端、展开图例及错误分类。
- Android：`ChatImageExportDeviceTest` 覆盖分页后末尾像素、保存挂起期间重新测量、错误图形后仍包含完整 SVG 和后续内容；`SvgBitmapExportTest` 覆盖延迟 SVG 绘制。仅在隔离 Debug 测试包运行，不调用模型。

验证结果：38 项 JVM 测试、9 个 JSXGraph/法阵浏览器场景和 3 个新增导出场景通过，应用及 Android 测试 Kotlin 编译通过，`git diff --check` 通过。新增浏览器报告位于 `app/build/reports/geometry-preview/export-results.json`。

JVM/桌面浏览器并不证明 Android 位图截取正确。当前 adb 没有连接设备，原生像素测试待执行；本轮没有构建或安装新的 QA 包。
