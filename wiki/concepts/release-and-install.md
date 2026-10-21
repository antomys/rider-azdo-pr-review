---
type: concept
title: "Release and install"
tags: [release]
sources: [src-engineering-notes]
created: 2026-10-20
updated: 2026-10-20
---
# Release and install

- Bump `pluginVersion` in `gradle.properties`, run [[build-and-verify]], then attach `build/distributions/rider-azdo-pr-review-<version>.zip` to a **private GitHub release** (`gh release create v<version> <zip>`).
- No Marketplace listing and no public plugin repository (owner's choice).
- **Install:** `scripts/install-plugin.sh` takes the latest release, `--version vX.Y.Z`, `--zip FILE` or `--build`, then restart Rider. It needs Rider 2026.2. Or use Settings | Plugins | ⚙ | Install Plugin from Disk.
- v0.1.0 was released on 2026-10-20 from `8fd864e`.

Sources: [[src-engineering-notes]]
