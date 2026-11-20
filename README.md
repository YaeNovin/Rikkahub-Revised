<div align="center">
  <img src="docs/icon.png" alt="Rikkahub Revised app icon" width="88" />
  <h1>Rikkahub Revised</h1>
  <p>An independent Android AI chat client based on <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a>.</p>
  <p>English · <a href="README_ZH_CN.md">简体中文</a></p>
  <p>
    <a href="https://github.com/YaeNovin/Rikkahub-Revised/releases/latest"><img alt="Latest stable release" src="https://img.shields.io/github/v/release/YaeNovin/Rikkahub-Revised?label=Release" /></a>
    <a href="https://developer.android.com/about/versions/oreo"><img alt="Android 8.0 or newer" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&amp;logoColor=white" /></a>
    <a href="https://github.com/YaeNovin/Rikkahub-Revised/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/YaeNovin/Rikkahub-Revised?style=flat&amp;logo=github" /></a>
    <a href="LICENSE"><img alt="AGPL-3.0 license" src="https://img.shields.io/badge/License-AGPL--3.0-blue" /></a>
  </p>
  <p>
    <a href="https://trendshift.io/repositories/178561?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-178561" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/178561/daily?language=Kotlin" alt="YaeNovin/Rikkahub-Revised | Trendshift" width="250" height="55" /></a>
  </p>
</div>

## 📲 Download

**[Download the latest APK from GitHub Releases](https://github.com/YaeNovin/Rikkahub-Revised/releases/latest)**

The latest stable release provides a versioned ARM64 APK and `SHA256SUMS.txt`. Open the [release archive](https://github.com/YaeNovin/Rikkahub-Revised/releases) for older builds. Revised uses the Android package ID `me.rerere.rikkahub.revised` and can be installed alongside upstream RikkaHub. Each app keeps its own data. In-place updates require the [Revised signing certificate](docs/RELEASE_SIGNING.md).

## ✨ What you can do

- **Chat with your models.** Connect OpenAI-, Gemini-, or Claude-compatible providers. Use streaming chat, images, documents, web search, and assistant profiles.
- **Keep the context you need.** Branch conversations, summarize long histories, and attach local knowledge bases, memories, or world books to an assistant.
- **Read rich output in chat.** Use native Markdown tables, search citations, formulas, syntax highlighting, charts, diagrams, music notation, and read-only ANSI previews.
- **Make the interface your own.** Use wallpaper-derived text colors, optional HEX swatches and quote highlighting, speech services, or the embedded Web UI.

Feature availability depends on the model, protocol, and preview surface. See the [documentation index](docs/README.md) and [rendering support matrix](docs/rendering-roadmap-20261120.md) for details about the current source.

<details>
<summary>📚 Release history and upstream baseline</summary>

- [2.4.8-revised.10 release notes](docs/release-notes-2.4.8-revised.10.md) and [verification record](docs/release-verification-2.4.8-revised.10.md)
- [All releases](https://github.com/YaeNovin/Rikkahub-Revised/releases) and [modification record](docs/MODIFICATIONS.md)
- The upstream baseline for this release line is [RikkaHub 2.4.8 at `8824e0e8`](https://github.com/rikkahub/rikkahub/commit/8824e0e841f2008b322ca8214a27a978e4b4abaa).

</details>

<details>
<summary>🛠️ Build from source</summary>

Install JetBrains JDK 21, Android SDK API 37, and the Web UI dependencies described in the [build guide](docs/building.md).

```bash
git clone --recurse-submodules https://github.com/YaeNovin/Rikkahub-Revised.git
cd Rikkahub-Revised
./gradlew :app:assembleDebug
```

On Windows, run `.\gradlew.bat :app:assembleDebug`. See the [project structure](docs/PROJECT_STRUCTURE.md) and [developer scripts](scripts/README.md) for test commands and module entry points.

</details>

## 💬 Support and credits

Rikkahub Revised is independently maintained. Use this repository’s [issues](https://github.com/YaeNovin/Rikkahub-Revised/issues) and [pull requests](https://github.com/YaeNovin/Rikkahub-Revised/pulls) for feedback. Include the app version and reproduction steps, keeping credentials and private chat content out of reports.

Rikkahub Revised is licensed under the [GNU Affero General Public License v3.0](LICENSE). See [NOTICE](NOTICE), [contributors](docs/CONTRIBUTORS.md), and [third-party notices](docs/THIRD_PARTY_NOTICES.md) for attribution and bundled licenses.
