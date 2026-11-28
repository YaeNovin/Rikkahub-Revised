# Rikkahub Revised 2.4.8-revised.13

更新内容：

- 修复部分图片在裁剪后无法设置头像的问题，先规范化图片来源，再进入裁剪和保存流程。
- 为 Gemini 模型增加 Responses 协议下的原始音频、视频附件入口。自动识别 OpenRouter；其他已确认支持相同扩展的供应商可手动开启，并提供格式、文件大小和取消检查。
- 改进引用来源：网页引用显示可点击的来源链接；知识库引用点击后打开抽屉，显示文档位置并高亮对应摘录。
- 将远程聊天图片和 GitHub 仓库信息改为缓存优先、点击加载或刷新，避免滚动和重复渲染触发隐式联网；版本检查改为手动触发。
- 新增正文 Ruby 拼音/假名、注音、IPA 和句法树，支持古日语竖排、纵中横及受控校勘注；同步适配设置、模型能力发现、网页预览和消息图片导出。
- 修复正文 LaTeX 与化学式直接显示源码的问题，并改善长竖排布局和校勘展开后的高度更新。

Updates:

- Fixed avatar setup failures for some cropped images by normalizing the image source before cropping and saving.
- Added original audio/video attachments for Gemini models through compatible Responses endpoints. OpenRouter is detected automatically; documented compatible gateways can be enabled manually, with format, size and cancellation checks.
- Improved citations with clickable web source titles and a knowledge-source drawer that shows document locations and highlights the cited excerpt.
- Made remote chat images and GitHub metadata cache-first and explicitly loaded or refreshed, preventing implicit requests during scrolling and repeated rendering. Update checks are now manual.
- Added inline Ruby, Bopomofo, IPA and syntax trees, plus vertical Japanese, upright short numbers and controlled critical apparatus. Settings, model capability discovery, web previews and message-image export share the supported rendering path.
- Fixed raw LaTeX and chemistry expressions appearing in prose, and improved long vertical layouts and expanded annotation heights.
