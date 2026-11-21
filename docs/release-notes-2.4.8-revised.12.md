# Rikkahub Revised 2.4.8-revised.12

更新内容：

- 新增 GitHub Token 安全配置与原生仓库卡片，支持仓库语言色条、分享、预览和保存图片，改善鉴权失败、限流及离线回退。
- 优化记忆清除与后台提取的同步，防止清除后被旧任务重新写入。限制查询向量等待时间，保留关键词检索回退。
- 新增 IPA 音标、Ruby 注音、逐词/词素释义、句法树和依存弧线渲染，支持独立开关、离线字体与模型能力发现。
- 增强乐谱附件、长谱分页和整谱矢量 PDF 导出，新增简谱和声与 MusicXML 功能和声兼容。
- 新增 MEI 古谱基础预览与设置开关，支持定量谱及纽姆音符。当前纽姆歌词仍保留在源码中，尚不参与刻谱和 PDF。
- 更新 MiniMax 国内聊天及语音默认地址为 `api.minimax.cn`，兼容迁移旧标准地址，保留国际平台和自定义中转配置。

Updates:

- Added secure GitHub token settings and native repository cards with language bars, sharing, preview and image export. Improved authentication, rate-limit and offline fallbacks.
- Prevented stale background extraction from restoring cleared memories, and bounded query-embedding waits while retaining keyword fallback.
- Added IPA, Ruby pronunciation, interlinear glosses, constituency trees and dependency arcs with individual switches, offline fonts and model discovery.
- Improved score attachments, long-score pagination and whole-score vector PDF export, including numbered-notation harmony labels and MusicXML functional harmony.
- Added basic MEI early-music previews and a settings switch for mensural notation and neume components. Neume syllable lyrics remain available in source and are currently omitted from engraving/PDF.
- Updated mainland MiniMax chat and speech defaults to `api.minimax.cn`, with compatible migration of old standard endpoints and preservation of international/custom proxy settings.
