---
type: concept
title: "Release and install"
tags: [release]
sources: [src-engineering-notes]
created: 2026-10-20
updated: 2026-10-21
---
# Release and install

- Bump `pluginVersion` in `gradle.properties` and add a line to `change-notes` in `plugin.xml`, run [[build-and-verify]], then attach `build/distributions/rider-azdo-pr-review-<version>.zip` to a **GitHub release** (`gh release create v<version> <zip>`).
- No Marketplace listing and no custom plugin repository (owner's choice). The GitHub repository is public (verified 2026-10-21), so the install one-liner needs no token.
- **Install:** `curl -fsSL https://raw.githubusercontent.com/antomys/rider-azdo-pr-review/master/scripts/install-plugin.sh | sh`, then restart Rider. Options go after `sh -s --`: `--version vX.Y.Z`, `--zip FILE`, `--build`. The script downloads the release zip with curl through the GitHub API (no `gh` needed) and needs Rider 2026.2. Or use Settings | Plugins | ⚙ | Install Plugin from Disk.
- v0.1.0 was released on 2026-10-20 from `8fd864e`; v0.1.1 (the security and performance fixes from PR #8) on 2026-10-21.
- 0.2.0 is prepared on branch `feat/review-state` (`pluginVersion = 0.2.0`, change notes, README) and in review; not released as of 2026-10-21.

Sources: [[src-engineering-notes]]
