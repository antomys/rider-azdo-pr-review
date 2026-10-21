---
type: concept
title: "Build, test and verify"
tags: [testing, release, platform]
sources: [src-engineering-notes, src-plan]
created: 2026-10-20
updated: 2026-10-20
---
# Build, test and verify

```
./gradlew test buildPlugin verifyPlugin -PplatformLocalPath=/Applications/Rider.app
```

- **Tests:** 68 (5 live tests skipped). Fixtures replay through `FixtureServer`; view models are tested with `FakeReviewProvider`.
- **Verifier:** must say Compatible, with no deprecated, experimental or internal API use.
- **Stale test classes** (`NoSuchMethodError`): delete `build/classes/kotlin/test` and `build/kotlin/compileTestKotlin`.
- **Sandbox IDE:** `./gradlew runIde -PplatformLocalPath=/Applications/Rider.app`. Restart it after changes (no hot reload). Don't switch branches while it runs; use `git worktree`.
- **UI changes:** check them in the sandbox IDE against the [[sandbox]], and compare with the product baseline.

Sources: [[src-engineering-notes]], [[src-plan]]
