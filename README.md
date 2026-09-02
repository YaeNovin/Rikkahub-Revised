<div align="center">
  <img src="docs/icon.png" alt="Rikkahub Revised app icon" width="100" />
  <h1>Rikkahub Revised</h1>

  <p>An independent, community-maintained Android AI chat client based on RikkaHub.</p>

  English | [Simplified Chinese](README_ZH_CN.md)

  [![Latest release](https://img.shields.io/github/v/release/YaeNovin/Rikkahub-Revised?display_name=tag&sort=semver)](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest)
  [![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
  [![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com/about/versions/oreo)
</div>

> [!IMPORTANT]
> Rikkahub Revised is a modified distribution of
> [RikkaHub](https://github.com/rikkahub/rikkahub). It is not an official
> RikkaHub release and is not endorsed or supported by the upstream
> maintainers. Revised-specific issues should be reported in this repository,
> not to the upstream project.

## Project Identity

This repository contains the complete source for Rikkahub Revised. The current
release line is based on upstream RikkaHub `2.4.8` at commit
[`8824e0e8`](https://github.com/rikkahub/rikkahub/commit/8824e0e841f2008b322ca8214a27a978e4b4abaa).
Later upstream releases are not automatically included.

| Item | Value |
| --- | --- |
| Distribution name | `Rikkahub Revised` |
| Android application ID | `me.rerere.rikkahub.revised` |
| Current release | [`2.4.8-revised.7`](https://github.com/YaeNovin/Rikkahub-Revised/releases/tag/2.4.8-revised.7) |
| Minimum Android version | Android 8.0 (API 26) |
| Source repository | `YaeNovin/Rikkahub-Revised` |
| License | GNU Affero General Public License v3.0 |

The distinct application ID allows Rikkahub Revised and upstream RikkaHub to
be installed on the same device. Their app data is separate, and this project
does not automatically import data from the upstream application.

## Download

Official Rikkahub Revised APKs are published only through this repository's
[GitHub Releases](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest).

| APK | Intended devices |
| --- | --- |
| `app-arm64-v8a-release.apk` | Most modern Android phones and tablets |
| `app-x86_64-release.apk` | x86_64 emulators and compatible devices |
| `app-universal-release.apk` | Universal fallback containing ARM64 and x86_64 native libraries |

Release asset filenames may include the release version suffix. For example,
the current `.7` release publishes
`app-arm64-v8a-release-2.4.8-revised.7.apk`; the updater detects the device ABI
from the asset name.

The in-app updater reads the latest public release from this repository and
offers an APK compatible with the device ABI. Release descriptions contain only
user-facing updates and fixes; package integrity and signing continuity are
verified separately during publication.

All future Revised releases must use the same signing certificate. Builds
signed with another key cannot update an existing Rikkahub Revised installation.
See [RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) for the public certificate record
and signing continuity policy. Private signing material is never stored in this
repository.

## What Revised Changes

Rikkahub Revised keeps the upstream Android client as its foundation and adds
or changes the following areas:

### Current Formal Release: 2.4.8-revised.7

- Added the OrcaRouter provider with official website and documentation links.
- Improved model- and protocol-aware parameters, reasoning depth, tool calls,
  and third-party compatible endpoints.
- Expanded workspace SAF/local-file operations, shell execution safeguards,
  prompt templates, placeholders, world books, memories, and entertainment mode.
- Added request statistics, high-precision timing diagnostics, and focused
  regression coverage.
- Fixed chat loading, stream recovery, conversation branches, and complex
  Markdown, Diff, LaTeX, Mermaid, and WebView rendering.
- Improved image-generation settings and full-screen previews, plus localized
  resources and Android compatibility handling.

- Added model-aware request parameters for OpenAI, Claude, Gemini, Grok, Qwen,
  and DeepSeek, plus sanitized diagnostics that omit credentials, prompts,
  schemas, and binary content.
- Added premature stream termination detection, corrected retry time budgets,
  and improved event-stream keepalive behavior.
- Added Gemini image-generation controls and unified source, inline, and
  full-screen previews for supported rich code blocks.
- Fixed crop cleanup before persistence completed, conversation switching after
  repeated settings changes, non-atomic navigation replacement, and misplaced
  provider model actions.

- Token-aware rolling context summaries, automatic context-window discovery,
  animated compression feedback, and context usage display.
- Local knowledge bases with document ingestion, chunking, hybrid retrieval,
  assistant bindings, RAG, source citations, and file previews.
- Expanded memory metadata, embedding-based retrieval, lexical fallback, and
  scoped memory tools.
- Provider capability metadata, embedding support, connection diagnostics,
  sanitized protocol traces, maximum reasoning controls with provider-specific
  parameter mapping, and safer custom-provider configuration handling.
- Tighter approval and privacy boundaries for workspace, clipboard, calendar,
  and screen-time tools.
- Separated backup scopes and safer S3/WebDAV archive restoration.
- Day-based cleanup for chat attachments and generated images while retaining
  conversation text.
- Interactive ECharts, ABC notation, Leaflet, and railroad-diagram rendering,
  plus Mermaid, LaTeX, Markdown, and WebView improvements.
- A Revised-owned update feed with ABI-aware APK selection.
- A separate application ID, isolated URI schemes and authorities, and
  opt-in Firebase integration.

For the auditable modification record, upstream comparison, and release
requirements, read [MODIFICATIONS.md](docs/MODIFICATIONS.md).

## Inherited Capabilities

The upstream foundation provides the core Android chat experience, including:

- OpenAI-, Google-, and Anthropic-compatible providers and custom endpoints.
- Streaming chat, multimodal input, document parsing, and message branching.
- MCP, web search providers, prompt variables, tools, and assistant profiles.
- Markdown, syntax highlighting, LaTeX, tables, and Mermaid rendering.
- Local memory, provider import/export, an embedded web interface, and the
  optional Linux workspace environment.

These inherited capabilities originate from the upstream project unless a
change is identified in [MODIFICATIONS.md](docs/MODIFICATIONS.md).

## Build From Source

Prerequisites:

- JDK 17
- Android SDK with API 37 and Build Tools 37
- Git with submodule support

Clone the complete source and initialize its submodule:

```bash
git clone --recurse-submodules https://github.com/YaeNovin/Rikkahub-Revised.git
cd Rikkahub-Revised
```

Build a development APK on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

On Linux or macOS:

```bash
./gradlew :app:assembleDebug
```

Run JVM tests with `./gradlew test` or `gradlew.bat test`. The Android app is
the `app` module; the remaining modules and common change locations are mapped
in [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md).

The chat request lifecycle is documented separately in
[English](docs/references/chat-generation-pipeline.en.md) and
[Simplified Chinese](docs/references/chat-generation-pipeline.zh-CN.md).

Release builds read signing values from the ignored local `local.properties`.
Do not commit keystores, passwords, `local.properties`, or a Firebase
configuration. Firebase and Crashlytics are disabled by default; a
maintainer-owned configuration matching the Revised application ID is required
to opt in.

## Contributing and Support

Bug reports and changes for Rikkahub Revised are welcome through this
repository's [issues](https://github.com/YaeNovin/Rikkahub-Revised/issues) and
pull requests. Please state whether a problem also occurs in upstream RikkaHub
and avoid including provider keys, account data, signing files, or private chat
content in reports.

Upstream documentation and community channels belong to the upstream project.
They should not be treated as support channels for Revised-only behavior.

## License and Attribution

Rikkahub Revised is distributed under the
[GNU Affero General Public License v3.0](LICENSE), consistent with its upstream
foundation. When distributing modified versions or providing the software over
a network, comply with the AGPL-3.0 source-availability requirements.

- [NOTICE](NOTICE) identifies the upstream project and the Revised distribution.
- [MODIFICATIONS.md](docs/MODIFICATIONS.md) records the material modifications.
- [CONTRIBUTORS.md](docs/CONTRIBUTORS.md) lists the people who directly build and
  maintain this independent repository.
- [THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) lists bundled third-party
  components and their licenses.
- [RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) records the public signing identity
  and release-key continuity requirements.

Copyright and authorship of unchanged upstream code remain with the upstream
contributors. This repository does not claim authorship of that work.
