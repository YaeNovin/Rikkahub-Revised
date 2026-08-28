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
