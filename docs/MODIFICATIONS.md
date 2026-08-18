# Rikkahub Revised Modification Notice

Notice date: 2026-08-18

This repository is a modified distribution of the upstream
[RikkaHub project](https://github.com/rikkahub/rikkahub). It is based on the
upstream `2.4.8` tag at commit
`8824e0e841f2008b322ca8214a27a978e4b4abaa`. The first revised release line is
`2.4.8-revised.1`; the current published release is `2.4.8-revised.3`.

This file describes the modified work as required for an auditable AGPL-3.0
release. It does not claim authorship of unchanged upstream code. Release checks
are maintained separately from user-facing release descriptions.

## 2.4.8-revised.3 Maintenance Changes / 维护变更

- English: Fixed stale update downloads and repeated installation prompts by
  using versioned download files and strictly newer Android version codes.
  中文：通过版本化下载文件和严格递增的 Android 版本码，修复旧安装包重复提示及
  更新安装后不生效的问题。
- English: Added device ABI matching for update packages and text/image preview
  support for knowledge-base files.
  中文：更新时按设备 ABI 匹配安装包，并为知识库文件增加文本与图片预览。
- English: Added a maximum reasoning level with protocol- and vendor-aware
  request mapping for OpenAI-, Google-, Anthropic-, and compatible model APIs.
  中文：增加“最高”推理深度，并针对 OpenAI、Google、Anthropic 及兼容模型接口按
  协议和厂商映射请求参数。
- English: Added day-based cleanup for chat attachments and generated images
  while preserving conversation text.
  中文：支持按天清理聊天附件与生成图片，同时保留聊天文本。

## 2.4.8-revised.2 Maintenance Changes / 维护变更

- English: Fixed automatic context-window discovery for OpenAI-, Google-, and
  Anthropic-compatible model lists. Explicit provider metadata takes priority,
  known model limits provide a fallback, and saved manual values are preserved.
  中文：修复 OpenAI、Google 与 Anthropic 兼容协议的上下文窗口自动获取；优先采用
  提供商返回值，在缺失时使用已知模型限制回退，并保留用户手动设置。
- English: Removed the upstream official-website entry from the About screen
  and made the displayed application name follow the Revised app resource.
  中文：从“关于”界面移除上游官网入口，并让界面显示名称跟随 Revised 应用资源。
- English: Added a bilingual automatic context-compression status with a
  continuous progress animation during rolling-summary refreshes.
  中文：滚动摘要自动刷新时显示中英文自动压缩状态，并提供持续进度动画。

## Major Modifications

- Replaced fixed message-count truncation with token-aware rolling context
  summaries, context-window discovery/configuration, context usage UI, and
  throttled streaming updates.
- Added local knowledge bases, document ingestion and chunking, vector and
  lexical retrieval, assistant bindings, RAG, source citations, and read-only
  knowledge tools.
- Extended memories with types, timestamps, source conversations, embeddings,
  semantic retrieval, lexical fallback, and scoped list/edit/delete tools.
- Added provider capability metadata, embedding support, connection and stream
  diagnostics, sanitized protocol traces, and secret-safe custom-provider
  import/export behavior.
- Tightened local-tool privacy boundaries for workspace/rootfs access,
  clipboard operations, calendar deletion, and screen-time results, with
  approval-policy tests.
- Split backup scopes for databases, settings/credentials, attachments, and
  workspaces; excluded rootfs; and added archive path validation for S3 and
  WebDAV restore flows.
- Added interactive ECharts, ABC notation, Leaflet, and railroad-diagram
  renderers; improved Mermaid, LaTeX, Markdown, and animated-background
  behavior; and hardened local WebView settings.
- Added database migrations and Room schemas through version 28, knowledge-base
  navigation, provider configuration UI, multilingual resources, focused tests,
  and architecture documentation.
- Added timestamped Debug/QA versions and an optional QA keystore override.
- Restored the in-app update card and reminder controls, replacing the upstream
  service with this fork's public GitHub Releases feed and device-compatible APK
  selection.
- Renamed the distribution to `Rikkahub Revised`, changed the release
  application ID to `me.rerere.rikkahub.revised`, isolated custom URI schemes,
  app-internal actions, Provider authorities, Debug/QA package IDs, and made
  Firebase integration opt-in for a future fork-owned configuration.

Implementation details are preserved in the source and Git history.

## Upstream Version Context

The newer upstream snapshot used during the 2026-08-17 audit is commit
`3b4b80a4173ea626422c7ec037af383b828f8623`, approximately the upstream
`2.4.10` line and 15 commits ahead of `2.4.8`. It was released after this fork's
development baseline and is only a compatibility/security review reference.
Those commits have not been merged into this working tree.

An upstream upgrade should be performed as a separate, reviewable change after
the current modifications are committed and tested. It is not a prerequisite
for accurately publishing the existing `2.4.8`-based source.

## Source Links in Release Builds

The About screen now defaults to the public revised repository and its
AGPL-3.0 license on the `main` branch:

```text
https://github.com/YaeNovin/Rikkahub-Revised
https://github.com/YaeNovin/Rikkahub-Revised/blob/main/LICENSE
```

The Gradle properties `rikkahub.sourceRepositoryUrl` and
`rikkahub.sourceLicenseUrl` remain available for mirrors and reproducibility
tests. Public binaries must point to the complete corresponding source, build
scripts, license, this modification notice, and applicable third-party notices.

## Build and Signing Notes

- `release` uses `2.4.8-revised.3` and signing values
  from ignored `local.properties` when supplied.
- The release application ID is `me.rerere.rikkahub.revised`; Debug and QA add
  `.debug` and `.qa` respectively so test builds do not replace a signed release.
- Debug and QA builds append an Asia/Shanghai build timestamp. The QA signing
  configuration inherits Android debug signing and only uses
  `../android-user-home/debug.keystore` when that optional file exists.
- Google Services and Firebase Crashlytics Gradle plugins are disabled by
  default because the ignored upstream `google-services.json` does not belong
  to the revised application ID. A maintainer-owned matching configuration can
  opt in with `--project-prop=rikkahub.enableFirebase=true`.
- Keystores, passwords, `google-services.json`, `local.properties`, IDE state,
  and build logs must not be committed.

The release certificate fingerprint and key-continuity rules are documented in
[RELEASE_SIGNING.md](RELEASE_SIGNING.md). The private key and recovery passwords
must remain outside the repository and be backed up offline.

## Update Distribution

The restored in-app updater checks only:

```text
https://api.github.com/repos/YaeNovin/Rikkahub-Revised/releases/latest
```

GitHub `404` is treated as no published update. Release tags use
`v2.4.8-revised.N`; each distributed update must increment both `N` and Android
`versionCode`. The release must be a non-draft, non-prerelease GitHub Release so
it is returned by `/releases/latest`.

The updater accepts APK assets only from this repository. It offers the first
matching ABI from `Build.SUPPORTED_ABIS` plus the Universal fallback and filters
out incompatible architectures. Official release assets must use these names:

- `app-arm64-v8a-release.apk`
- `app-x86_64-release.apk`
- `app-universal-release.apk`

Android still enforces signing continuity during installation. Every public APK
must use the certificate recorded in `RELEASE_SIGNING.md`. Release descriptions
remain limited to user-facing updates and fixes; asset integrity, signing, and
build-environment details are verified separately during publication.

## License

The modified work is distributed under GNU AGPL-3.0, consistent with the
upstream project. No `-or-later` grant is inferred from the repository's current
license declaration. See [LICENSE](../LICENSE), [NOTICE](../NOTICE), and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
