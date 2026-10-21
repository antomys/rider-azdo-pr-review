---
type: source
title: "Risks"
tags: [process, azure-devops, platform, open-question]
authors: [owner, agent]
raw: docs/risks.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-21
---
# Risks

R1–R12 with impact, likelihood and mitigations. The ones agents run into most:

- **R1 PAT handling:** `PasswordSafe` only, never log the `Authorization` header. Global PATs stop working on 1 Dec 2026.
- **R2 rate limits:** honour `Retry-After` (the client retries up to 3 times).
- **R3 platform API churn:** one platform branch (262.*), the verifier stays clean, public APIs over internal ones.
- **R3a:** the platform's kotlinx.serialization is an internal module, yet the verifier reports Compatible.
- **R4 diff limits:** no server hunks, position drift after pushes, binaries.
- **R5 AI data egress:** implemented for the Claude Code adapter in 0.2.0 (off by default, consent on every run, exclude globs, no tools, minimal environment without the PAT, findings validated and previewed). Not covered: the Copilot CLI adapter, path include globs. See [[ai-review]].
- **R6 licenses:** copy patterns, not code.
- **R7 coexistence** with the bundled Azure DevOps plugin.
- **R8 unverified API behaviors.**
- **R9 local review state:** marks live in one IDE installation ([[reviewed-marks]]).
- **R10 change detection:** `lastMergeSourceCommit` is a proxy; behavior after rebase or force-push still to confirm ([[update-view]]).
- **R11 team assignments:** direct teams only, one request per team ([[assigned-to-me]]).
- **R12 PAT lifecycle:** a PAT can't read its expiry; rejected tokens re-prompt ([[sign-in]]).

Related: [[azure-devops-rest]], [[intellij-platform]].
