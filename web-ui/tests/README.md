# WebUI regression tests

`auth-session.test.tsx` also covers the six settings sections, theme persistence, settings SSE updates, the assistant dialog's model selection, unknown sections, login protection and mobile layout. Screenshots are saved in `build/settings-desktop.png`, `build/settings-mobile.png` and `build/assistant-settings-mobile.png`. These use a synthetic backend, not a physical phone.

From `web-ui`, use the pnpm version pinned in package.json:

```sh
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm run test
pnpm run build
pnpm run test:browser
pnpm exec oxlint app tests
```

`test` exercises stream retry/backoff/cancellation/authentication failures with Node's test runner. `test:browser` requires a current `build/client`, serves it with a synthetic backend on a temporary loopback port, and tests production UI snapshot recovery including a delayed stale REST response. A second browser test renders the actual preview iframe component with hostile HTML/SVG and checks parent-origin isolation while preserving local script interaction.

On Windows, browser tests use installed Microsoft Edge. On other platforms, install Chromium using `pnpm exec playwright install chromium`. Set `PLAYWRIGHT_CHANNEL` to override the installed browser channel. These tests do not read real provider keys or send prompts to an AI provider.

The corresponding Android tests are `WebSettingsDtoTest` (persisted settings/recursive response allowlist) and `ChatSuggestionWebDtoTest` (existing conversation DTO behavior):

```sh
./gradlew :app:testDebugUnitTest --tests '*WebSettingsDtoTest' --tests '*ChatSuggestionWebDtoTest'
./gradlew :web:testDebugUnitTest --tests '*PreviewOriginTest'
```

The settings projection intentionally lists fields at every nesting level. When adding browser-visible settings, update `WebSettingsDto.kt`, `app/types/settings.ts`, and the projection tests together. Never pass a complete provider, MCP, assistant or persisted Settings object through the event stream.

## Explicit physical-device tests

Use only an isolated Debug/QA app with a dedicated password and USB forwarding. These scripts are excluded from ordinary `test:browser` runs:

```sh
# Set WEB_DEVICE_PASSWORD, optionally WEB_DEVICE_URL (default http://127.0.0.1:18080).
pnpm exec tsx tests/device-web-session.ts
# Also set ANDROID_SERIAL and open the Debug app Web settings password field.
pnpm exec tsx tests/device-web-lifecycle.ts
# Also forward 18081 to the QA phone's 8081, for port restart/restore coverage.
pnpm exec tsx tests/device-web-configuration.ts
```

The session test creates and deletes a small attachment, checks real JWT/Cookie revocation and SSE closure, and saves browser evidence under `docs/local`. The lifecycle test temporarily appends one character to the dedicated password using UI-tree-derived coordinates and restores it. Neither script sends AI prompts. `WebAuthSessionsTest` covers expiry with an injected clock, revocation, server restart, bounded session registry and cookie attributes.

The configuration test uses the real APK management API and bundled WebUI. It creates a disabled provider/chat model, search configuration, disabled MCP server and temporary Skill, checks optimistic conflicts and secret redaction, saves title settings through Edge, changes the listening port to 8081, logs in again, and restores all settings in `finally`. Inspect `device/results.json` and the process exit code; cleanup success alone does not mean assertions passed. Defaults: `WEB_DEVICE_URL=http://127.0.0.1:18080`, `WEB_DEVICE_SECOND_URL=http://127.0.0.1:18081`. `WEB_EVIDENCE_DIR` overrides the evidence directory. Keep the phone app in the foreground. No external AI generation is performed.

For a fresh isolated APK, build with `./gradlew -I scripts/web-settings-qa.init.gradle :app:assembleDebug`. Its application ID is `me.rerere.rikkahub.revised.websettingsqa`. Enable password authentication and localhost-only port 8080 in that app, then add `adb -s SERIAL forward tcp:18080 tcp:8080` and `adb -s SERIAL forward tcp:18081 tcp:8081`. Remove those forwards and stop the QA service after testing. Do not uninstall a user's existing app to resolve signing conflicts.

`WebConfigurationTest` covers native configuration merge, revision conflicts, write-only values, excluded image/video models, reference checks and validation. Run it with `./gradlew :app:testDebugUnitTest --tests '*WebConfigurationTest'`. The browser auth/settings test also checks real Enter/Shift+Enter behavior, CRUD forms, dirty drafts and Ktor-compatible SSE error content negotiation.

`PreviewOriginTest` starts the real Ktor CIO server on a temporary loopback port. It checks that opaque `Origin: null` reads, writes, deletes and preflights are forbidden even without authentication, while ordinary reads and same-origin writes remain usable. Standalone file/data pages and sandboxed previews must not call the app API; open the served WebUI instead.

## Background / screen-off device regression

Only use the isolated `me.rerere.rikkahub.revised.websettingsqa` package. Set `ANDROID_SERIAL`, `WEB_DEVICE_PASSWORD`, and optionally `ADB_PATH`, `WEB_DEVICE_URL` and `WEB_EVIDENCE_DIR`:

```sh
pnpm exec tsx tests/device-web-background.ts
pnpm exec tsx tests/device-web-background-flow.ts
```

The first test compares foreground, launcher background and screen-off HTTP access and captures thread/service state. `WEB_BACKGROUND_SECONDS` controls polling duration per background phase (default 20); `WEB_BACKGROUND_IDLE_SECONDS` adds a quiet period before probing each phase (default 0). It wakes and brings the QA app back afterward. Capture both failing and passing evidence when diagnosing OEM restrictions.

The flow test runs a deterministic local chat-completions SSE endpoint on desktop port 18090 and installs a temporary `adb reverse` for the QA model. It uses real production WebUI message submission, native generation, streaming, persisted history/reload, assistant selection and settings writes while the phone is backgrounded and screen-off. It restores settings, removes conversations/provider and reverse mapping in `finally`. No external model calls are needed. It requires a QA assistant that follows the global model and another assistant for selection testing.

On the validated vivo device, ordinary Android battery exemption and a foreground service/wake lock do **not** prevent the OEM freezer. Select app info → battery → background power management → allow background power consumption for the QA package when testing the allowed case; restore its prior selection after tests. For direct Wi-Fi testing temporarily enable the QA server's LAN binding, use the phone's actual address, and restore loopback binding afterward. See `docs/web-background-access-fix-2026-10-06.md`.
