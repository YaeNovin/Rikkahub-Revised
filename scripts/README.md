# Build Scripts

Run the QA package build from the repository root:

```powershell
.\scripts\build-qa.ps1
```

The script uses normal Gradle dependency resolution and retries only failures
that look like repository or network outages. It intentionally does not force
`--refresh-dependencies`, because refreshing every artifact on every build is
slow and can turn a transient repository issue into a long failure.

Keep `GRADLE_USER_HOME` pointed at one stable, writable directory on the
machine. Switching between two cache directories makes Gradle download the
wrapper, Kotlin DSL plugin, and dependency graph again; it is not a source
build failure.

For a deliberate cache-only build:

```powershell
.\scripts\build-qa.ps1 -Offline -MaxAttempts 1
```

`-Offline` and `-RefreshDependencies` are mutually exclusive. A missing
artifact in offline mode is reported immediately instead of being mistaken for
a source compilation failure.

## Renderer regressions and asset build

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
| `verify-html-dependencies.cjs` | `HtmlPreviewDependenciesTest`; dependency loading and errors |
| `verify-html-loading.cjs` | `HtmlLoadingRegressionTest`; loading, failures and readable fallback |
| `verify-fullscreen-pan.cjs` | `WebViewPageTest`; pan, scale, reset and background toggle |
| `AndroidLatexProbe.java` | Compile with app classes and convert to DEX before running on Android; see the [LaTeX notes](../docs/latex-composer-fixes.md) |
| `katex-browser-global.cjs` | Build adapter sharing the page's KaTeX instance; required by the [bundled asset recipe](../docs/THIRD_PARTY_NOTICES.md) |

Browser tests intercept network requests and use bundled assets or fixtures.
They do not verify Android WebView lifecycle/GPU/touch behavior, nor do they
call a paid model. `node --check` verifies JavaScript syntax only.
