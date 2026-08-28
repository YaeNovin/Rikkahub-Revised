# HugeIcons Compose 1.3

This directory contains the fixed AAR used by the app so builds do not depend
on a transient JitPack compilation.

- Upstream: https://github.com/rikkahub/hugeicons-compose
- Source tag: `1.3`
- Source commit: `80cc7a83c686d9729f410a71b75fc52de0773f55`
- Artifact: `hugeicons-compose-1.3.aar`
- SHA-256: `19EC2878F4B36C8D5570F45B96C8A6A162FD7B4E41B94CA28FD93B37175F02BE`

The AAR is consumed directly by `app/build.gradle.kts`; its Compose runtime
dependencies continue to come from the app's version catalog and Compose BOM.
