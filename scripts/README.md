# Build Scripts

Run the QA package build from the repository root:

```text
python scripts/build_qa.py
```

The script uses normal Gradle dependency resolution and retries only failures
that look like repository or network outages. It intentionally does not force
`--refresh-dependencies`, because refreshing every artifact on every build is
slow and can turn a transient repository issue into a long failure.

On this Windows checkout, the script automatically reads
`../build-resources/resources.json`. It uses that directory's Gradle, JDK,
Android SDK, dependency cache, local Maven mirror and matching AAPT2. This
changes only the build subprocess environment; other projects and the global
`GRADLE_USER_HOME` remain independent. Explicit toolchain arguments take
precedence. Without the configuration file it uses the usual Gradle wrapper.

`RIKKAHUB_BUILD_RESOURCES` can select another resource-store directory. Keep
one stable writable Gradle user home for this project rather than making a new
cache for each test. Direct wrapper/IDE builds must select the same Gradle user
home themselves; the Python entry point configures it automatically.

For a deliberate cache-only build:

```text
python scripts/build_qa.py --offline --max-attempts 1
```

`--offline` and `--refresh-dependencies` are mutually exclusive. A missing
artifact in offline mode is reported immediately instead of being mistaken for
a source compilation failure.

Optional toolchain arguments are `--gradle PATH`, `--java-home PATH`,
`--gradle-user-home PATH`, `--init-script PATH`, and repeated `--tests PATTERN`.
Pass Gradle task names before options. Build logs are under `build/reports/qa-build/`.

Portable environment setup and optional resource-store configuration are
documented in [the build guide](../docs/building.md). One-time disk migration
tools and machine-specific reports remain local; do not run another maintainer's
cleanup plan on a fresh checkout.

The migrated QA tools use Python's standard library and do not invoke PowerShell:

Python QA scripts resolve the default `adb` from the SDK in
`../build-resources/resources.json`, ahead of the inherited PATH. `--adb PATH`
remains available as an explicit override. The build subprocess also places that
SDK's platform-tools, command-line tools and emulator first on its PATH.

```text
python scripts/verify_custom_search_ui.py --captures app/build/outputs/custom-search
python scripts/verify_floating_chat_controls.py --serial SERIAL --adb PATH_TO_ADB
python scripts/capture_android_flow.py --serial SERIAL --flow scroll --output build/qa/scroll
python scripts/capture_android_trace.py --serial SERIAL --output build/qa/trace
python scripts/analyze_android_trace.py build/qa/trace/TRACE.pftrace --processor PATH_TO_TRACE_PROCESSOR
python scripts/verify_dark_appearance.py --serial SERIAL --adb PATH_TO_ADB
python scripts/verify_appearance_modes.py --serial SERIAL --adb PATH_TO_ADB
python -m unittest discover -s scripts -p test_android_qa.py
```

The navigation tool accepts only the isolated appearanceqa package. The frame
capture tool checks the foreground app and populated scroll region, takes tap
coordinates from the UI tree, and restores the previous device stay-awake setting.
Existing specialist scripts outside this migration retain their current runtimes.

`verify_dark_appearance.py` requires Pillow and the isolated debug build with
`RichTextRegressionActivity`. It checks dark and AMOLED surfaces, toggles all
four text palette modes, and confirms the selected foreground in screenshot pixels.
`capture_android_trace.py` pairs a bounded scroll capture with a 25-second Perfetto
trace; host analysis uses the official Perfetto trace processor.

`verify_appearance_modes.py` requires Pillow and the isolated debug build made
with `scripts/appearance-regression.init.gradle`. It opens
`AppearanceModeRegressionActivity`, which uses the production root theme and
global material components without changing the user's preferences. The default
matrix covers liquid glass and mica on dark, absent and bright backgrounds,
including repeated text-mode switches. It checks screenshot pixels for page,
inherited, card, explicit setting text, real user bubbles and message bodies. Add `--amoled` to repeat with
pure black surfaces; `--materials`, `--backdrops` and `--output` select the matrix
and artifact directory. The script restores the device's stay-awake setting.

`capture_sidebar_transition.py --serial SERIAL --adb PATH_TO_ADB --output build/qa/sidebar`
records four seconds around a sidebar-to-settings handoff in the installed QA.
It uses current UI-tree bounds, leaves preferences intact and restores the previous
stay-awake setting. Optional `--ffmpeg PATH` produces a contact sheet; it needs a
full FFmpeg build with MP4 decoding and the `tile` filter.

`verify_palette_state.py --serial SERIAL --adb PATH_TO_ADB` opens the isolated
appearance fixture with `--es scenario state`, scrolls its list, then toggles text
modes, the page background handoff and dark/light mode. It checks both the exact
scroll anchor and a remembered instance token across glass, mica and opaque themes.
It does not require AndroidX instrumentation or change real QA preferences.

For native ANSI gestures, open that isolated fixture at its initial position,
forward its `webview_devtools_remote_PID` socket to a free port, then run:

```text
node scripts/verify-terminal-device.cjs PORT PATH_TO_ADB SERIAL
```

The device script checks the fixed page marker, uses fresh UI-tree bounds for
real adb swipes, verifies horizontal movement and parent vertical scrolling,
and confirms that the keyboard remains closed. Remove the adb forward afterward.

## Targeted Lint and native library verification

First-batch Lint checks are opt-in and leave the default full Lint configuration unchanged:

```powershell
.\gradlew.bat :app:lintRelease --init-script scripts/lint-batch1.init.gradle --no-configuration-cache
.\scripts\verify-workspace-termux.ps1 -ApkPath app/build/outputs/apk/qa/app-universal-qa.apk -Variant qa
```

Build the selected APK variant first. The native check reads ELF load alignment
from the APK and compares Termux with the matching merged/workspace and stripped
build outputs. It does not install or execute the APK, modify the archive, or
replace a 16 KiB device test. Reports and limits are described in the
[first-batch repair record](../docs/lint-remediation-batch1.md).

The [second-batch record](../docs/lint-remediation-batch2.md) covers configuration,
window, accessibility, and database changes. Check both batches together with:

```powershell
.\gradlew.bat :app:lintRelease --init-script scripts/lint-batch1.init.gradle --init-script scripts/lint-batch2.init.gradle --no-configuration-cache
```

The combined report is written to `app/build/reports/lint-batch2.*`; this is a
targeted check, not a replacement for full Lint.

The third-batch script checks the three deterministic maintenance issues
`EmptySuperCall`, `UnnecessaryArrayInit`, and `Typos`:

```powershell
.\gradlew.bat :app:lintRelease --init-script scripts/lint-batch3.init.gradle --no-configuration-cache
```

Pass all three init scripts when validating the combined first-to-third scope.

Regenerate the adaptive-icon monochrome layers after changing the foreground
assets with:

```powershell
python .\scripts\generate-monochrome-icons.py
```

Audit missing string keys without modifying resource files:

```powershell
.\scripts\audit-locale-resources.ps1
```

## Renderer regressions and asset build

Native Markdown table checks use the same isolated Debug build as citation QA:

```text
python scripts/qa_markdown_tables.py --serial SERIAL --output build/reports/markdown-tables-device
```

The script checks alignment, wrapped-row boundaries, wide-table horizontal
gestures, exact citation callbacks, streaming and 130% font scaling. See the
[table guide](../docs/markdown-tables.md). Build and install the matching
`--performance-probe` Debug APK before running it.

Search citation regression on a connected device uses an isolated Debug APK:

```text
python scripts/build_qa.py :app:assembleDebug --performance-probe --offline --max-attempts 1
python scripts/qa_markdown_citations.py --serial SERIAL --output build/reports/markdown-citation-device
```

Install the built `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` before
running the device script. It requires the isolated `renderperf` package and a
version matching local build metadata. It checks native/HTML rendering in both
themes, exact citation callbacks, table citations, long press, streamed completion,
literal code and scrolling stability. It uses synthetic sources, preserves the QA
app's data and restores the previous device stay-awake setting. See the
[repair and device record](../docs/markdown-citation-repairs-20261119.md).

These scripts use repository-relative paths; keep them with the source. They
write fixtures, reports and screenshots below `app/build/reports/`, which is
excluded from version control. Most browser checks require Node.js, Playwright
on Node's module search path, and Microsoft Edge (`channel: msedge`). The map
check also accepts `MAP_TEST_BROWSER` for a Chromium executable.

Generate the HTML fixtures with the corresponding JVM test before running a
browser script. For example:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HtmlLoadingRegressionTest'
node scripts/verify-html-loading.cjs
```

| Script | Fixture producer / purpose |
| --- | --- |
| `verify-web-renderers.cjs` | `WebRendererRegressionTest`; syntax and mocked API checks, not browser QA |
| `verify-svg-renderer.cjs` | `RawSvgTest`; SVG viewport and export regressions |
| `verify-diagram-regressions.cjs` | `DiagramSourceTest`; Mermaid/railroad source recognition |
| `verify-geographic-renderer.cjs` | `WebRendererRegressionTest`; offline maps, fallback and resize |
| `verify-additional-renderers.cjs` | `AdditionalRendererTest`; music, chemistry, Vega and WaveDrom |
| `verify-html-dependencies.cjs` | `HtmlPreviewDependenciesTest`; author scripts/CDN dependencies stay disabled in static HTML |
| `verify-html-policy.cjs` | `HtmlRenderPolicyTest`; HTML/SVG/Markdown injection isolation and safe map/chart labels |
| `verify-html-loading.cjs` | `HtmlLoadingRegressionTest`; loading, failures and readable fallback |
| `verify-fullscreen-pan.cjs` | `WebViewPageTest`; pan, scale, reset and background toggle |
| `verify-terminal-preview.cjs` | `TerminalPreviewTest`; offline read-only xterm, ANSI, resize, input isolation and fallback; see the [feature guide](../docs/chat-terminal-preview.md) |
| `verify-geometry-preview.cjs` | `GeometryPreviewTest`; JSXGraph/SVG, bounded expression evaluation, gaps, sliders, zoom, animation lifecycle and CSP; see the [feature guide](../docs/math-plot-and-sigil.md) |
| `verify-pattern-preview.cjs` | `PatternPreviewTest`; real p5.js Canvas 2D, shared geometry, deterministic mandalas, roses/fractals, budgets, lifecycle, CSP and screenshot checks (Playwright + pngjs); see the [feature guide](../docs/procedural-patterns.md) |
| `verify-decorative-effects.cjs` | `DecorativeEffectsTest`; particle pixels, layer reveal, native environment constraints, adaptive quality, path reuse, export freeze and lifecycle; see the [performance guide](../docs/decorative-effects-and-performance.md) |
| `verify-geometry-appearance.cjs` | `GeometryAppearanceTest`; decorative-only backgrounds, actual alpha pixels, CDP pinch/pan/cancel, state retention and fullscreen layout; `geometry-gesture-helpers.cjs` provides shared touch input; see the [guide](../docs/geometry-background-and-gestures.md) |
| `verify-three-spike.cjs` | Debug-only `ThreeSpikeTest`; shared-geometry p5/Three comparison, GPU PNG, fallback visibility, context recovery and disposal; [feasibility report](../docs/threejs-feasibility-spike.md) |
| `verify-three-spike-device.cjs` | Isolated debug WebView CDP checks; pass the adb-forwarded port and a documented mode; `three-spike.init.gradle` keeps the test package separate from QA |
| `AndroidLatexProbe.java` | Compile with app classes and convert to DEX before running on Android; see the [LaTeX notes](../docs/latex-composer-fixes.md) |
| `katex-browser-global.cjs` | Build adapter sharing the page's KaTeX instance; required by the [bundled asset recipe](../docs/THIRD_PARTY_NOTICES.md) |

Browser tests intercept network requests and use bundled assets or fixtures.
They do not verify Android WebView lifecycle/GPU/touch behavior, nor do they
call a paid model. `node --check` verifies JavaScript syntax only.

## P0 development baseline device checks

Build the isolated renderer fixture:

```text
python scripts/build_qa.py :app:assembleDebug --init-script scripts/p0-baseline-regression.init.gradle --offline --max-attempts 1
```

The Debug application ID is `me.rerere.rikkahub.revised.baselineqa`. Install its
ARM64 APK explicitly, then run:

```text
python scripts/qa_baseline_alignment.py --serial <device-serial>
```

This drives `BaselineAlignmentActivity` using generated sample text, role/theme
buttons and exact UI text assertions. It checks user/assistant cache isolation,
soft line breaks, pasted source across light/dark themes, and literal inline
code. Results and screenshots go to `build/reports/p0-device/`.
The script does not access user conversations or clear the installed QA data.

### 语言学渲染

- `verify-linguistic-renderers.cjs`：读取 JVM 生成的生产 HTML，离线核对五类渲染、深浅色、大字体、RTL、图形边界与导出。
- `linguistic-qa.init.gradle`：独立 `.revised.linguisticqa` Debug 包与进程内 Kotlin 编译，不修改常用 QA 数据。
- `qa_linguistic_renderers.py`：ADB 外部驱动 Activity 启动、截图与源码 UI 检查，应用内断言使用生产组件；完成后恢复临时亮屏设置。构建与命令见 [语言学说明](../docs/linguistic-rendering.md)。

### 音乐学渲染

- `verify-music-renderers.cjs`：`MusicScoreRenderingTest` 生成生产 HTML；离线验证翻页、系统完整性、屏宽重排、MusicXML 功能和声、MEI 定量谱/纽姆谱、逐页 SVG 与整条消息导出。
- `music-qa.init.gradle`：独立 `.revised.musicqa` Debug 包；合成谱面与常用 QA 数据隔离。
- `qa_music_renderers.py`：通过 ADB 驱动 `MusicRenderingDeviceTest`，检查深浅色、翻页后整谱 PDF 页数和每页墨迹、Android 原生 XML/MXL 解析与模型附件上下文，收集截图/PDF并恢复亮屏设置。命令与结果见 [音乐学说明](../docs/music-rendering.md)。
