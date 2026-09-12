# 全屏背景、Stack 与离线 GeoJSON（2026-09-10）

## 全屏背景

高级外观设置 → 全局背景新增 **全屏预览使用全局背景**。全屏预览内的背景按钮操作同一个持久设置；关闭后显示主题底色，不会透出下层聊天。全局背景的启用状态、不透明度、虚化和页面样式继续生效；页面样式为“不透明”时不显示壁纸。

原来开关只修改网页 CSS，原生预览窗口仍绘制纯色底，且开关没有保存。现在在预览窗口中明确绘制全局背景，并分离聊天助手与预览背景的作用域，控制台菜单也跟随预览背景。

页面加载结束后重新同步 CSS，解决切换时脚本尚未初始化而未生效的问题。开关不重新加载 HTML，不销毁 WebView，不重置缩放或拖动。图形自身的纸面保留，以确保复杂壁纸上的文字可读；地图本身的海洋和陆地不会被背景开关抹去。

## Stack 铁路图

旧白名单和 railroad-diagrams 1.0.0 均不支持 Stack。现已使用官方提交 `736dec7cc847530ab7d498b1a3331e61b1752ada` 的实现，并保留现有主题 CSS。

`Stack(A, B, C)` 是依次连接的多行顺序布局，不能替换成横向 Sequence 或表示备选项的 Choice。现在支持 Diagram 内的 Stack、单独 Stack 入口及 JSON `{"type":"stack","items":[...]}`；空 Stack 和不在白名单内的调用仍返回明确错误，不执行任意脚本。

来源：https://github.com/tabatkins/railroad-diagrams/blob/736dec7cc847530ab7d498b1a3331e61b1752ada/railroad.js 。原许可证为 CC0，已保留源文件声明。打包仅移除 ESM 的 export 关键字、加 IIFE，并导出官方函数工厂；不修改其布局算法。

## 离线 GeoJSON

- 标准 GeoJSON、自动识别的 JSON 地理数据默认不再请求 OpenStreetMap 瓦片。
- 复用项目已有 `assets/html/maps/world.json`，无需新增大型瓦片包。为旧 ECharts 数据补齐 `Feature.type` 后由 Leaflet Canvas 渲染在独立底图层，保留坐标、线路和标记的交互。
- 提供陆地、海洋及国家边界的离线概览；**不包含街道、建筑或地址细节**。只有点数据时默认从区域尺度显示，避免放大到本地数据不具备的街道尺度。
- `basemap: false` / `"none"` 仍关闭底图；`basemap: "offline"` 可明确指定离线。显式 `tileUrl` 继续作为用户选择的在线数据源，失败时显示提示并补上离线概览。任意 HTML 内作者自行调用的网络地图不会被静默改写。
- 离线资源加载异常仍保留用户数据图层，并明确提示。浏览器验证会检查真实地形像素，不仅检查存在一个空地图容器。

## 验证

已通过 QA Kotlin 编译、53 项相关 JVM 测试、20 个图形浏览器场景、32 个地理图浏览器场景、全屏拖动/缩放/背景开关回归，以及 12 类渲染页面的 JavaScript 语法与模拟接口检查。浏览器拦截外部请求；默认地图只读取应用内资源。Android 原生背景与触控的最终外观仍需真机复测，本轮不构建或覆盖常用 QA 包。

结果截图：`app/build/reports/geographic-renderer/default-basemap.png`（离线区域视角）、`offline-world.png`（离线世界地图）及 `app/build/reports/diagram-regressions/railroad-stack.png`（Stack 顺序换行）。
