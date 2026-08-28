# Rikkahub Revised Release Signing

This document records the public identity and continuity requirements for
official Rikkahub Revised Android packages. It contains no private key or
password.

## Application Identity

- Display name: `Rikkahub Revised`
- Release application ID: `me.rerere.rikkahub.revised`
- Debug application ID: `me.rerere.rikkahub.revised.debug`
- QA application ID: `me.rerere.rikkahub.revised.qa`
- Custom URI scheme: `rikkahub-revised://`

Changing the release application ID again creates another independent Android
application. The revised ID deliberately installs alongside upstream RikkaHub;
the two applications do not share or automatically migrate app data.

## Release Certificate

- Subject: `CN=Rikkahub Revised, OU=Release Signing, O=Rikkahub Revised`
- Key: RSA 4096-bit
- Certificate signature algorithm: SHA256withRSA
- Certificate SHA-256:
  `6C:69:B3:B5:F8:20:6C:C7:21:08:D7:81:4E:76:0A:D6:2D:3D:8D:92:E3:02:02:03:B1:25:0C:E3:76:50:E6:FE`
- Valid from: 2026-08-18 11:58:30 +08:00
- Valid until: 2054-01-03 11:58:30 +08:00

Every official APK update must be signed by this same key. Losing the private
key or either password prevents normal Android updates to existing installs.
The private keystore and credential recovery file must remain outside Git,
must not be attached to a release, and need at least two encrypted offline
backups under maintainer control.

## Local and CI Configuration

The Android build reads these keys from ignored root `local.properties`:

```properties
storeFile=/absolute/path/outside/the/repository/release.jks
storePassword=REDACTED
keyAlias=rikkahub-revised-release
keyPassword=REDACTED
```

CI should reconstruct an equivalent temporary signing configuration from
encrypted repository secrets, then destroy the temporary files after the job.
Never print credentials in build logs.

## Public Release Requirements

Before publishing an APK or AAB:

1. Publish the complete corresponding source under AGPL-3.0 at a stable tag or
   commit, including `LICENSE`, `NOTICE`, `docs/MODIFICATIONS.md`, build scripts,
   and applicable third-party notices.
2. Confirm the embedded source and license URLs resolve to
   `https://github.com/YaeNovin/Rikkahub-Revised` and its `main` branch license.
3. Run tests, Android Lint, a signed Release build, certificate verification,
   dependency-license review, secret scanning, and device installation tests.
4. Verify each uploaded asset against its local build and verify the APK
   certificate against the fingerprint above. Keep user-facing release
   descriptions limited to updates and fixes.
5. Keep `versionCode` strictly increasing for every distributed update.

## GitHub Release Contract

- Repository: `https://github.com/YaeNovin/Rikkahub-Revised`
- API: `https://api.github.com/repos/YaeNovin/Rikkahub-Revised/releases/latest`
- First tag/version: `v2.4.8-revised.1` / `2.4.8-revised.1`
- Current tag/version: `v2.4.8-revised.6` / `2.4.8-revised.6`
- APK assets: `app-arm64-v8a-release.apk`, `app-x86_64-release.apk`, and
  `app-universal-release.apk`

Publish each user-facing update as a full GitHub Release, not a draft and not a
GitHub prerelease. The app treats a `404` from `/releases/latest` as no update,
compares tags after accepting an optional leading `v`, and offers only the
device's preferred ABI plus the Universal fallback. The updater never downloads
an upstream RikkaHub APK.
