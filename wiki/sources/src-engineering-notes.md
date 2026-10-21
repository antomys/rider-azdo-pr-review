---
type: source
title: "Engineering notes"
tags: [platform, azure-devops, release, testing]
authors: [owner, agent]
raw: docs/engineering-notes.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-21
---
# Engineering notes

Facts verified while building 0.1.0 and 0.2.0: build and checks, sandbox IDE habits, IntelliJ Platform pitfalls, Azure DevOps REST behavior, the Claude Code CLI, and release.

- Build, verifier and test rules → [[build-and-verify]]
- Platform pitfalls (JTree protected fields, `InplaceButton` in inlays, popups at the bottom, …) → [[intellij-platform]]
- REST facts (votes need `isRequired`, draft PRs reject votes, avatars, labels, mentions) → [[azure-devops-rest]]
- Release and install → [[release-and-install]]
- 0.2.0 additions (2026-10-21):
  - `UnifiedDiffViewer` line transfer, viewer switching and rediff → [[unified-diff]], [[intellij-platform]];
  - list context menu (`PopupHandler.installSelectionListPopup`), modal dialogs from coroutines → [[intellij-platform]];
  - teams `?$mine=true`, reviewer filters that don't expand teams, `reviewers[]` fields → [[assigned-to-me]];
  - iteration commits (`top`/`skip`), `$compareTo` deltas → [[update-view]];
  - reviewed marks file and key → [[reviewed-marks]];
  - token failures (302 to `_signin`, 404 forms, repository check) → [[sign-in]], [[azure-devops-rest]];
  - Claude Code CLI 2.1.306 flags, environment, process-tree kill, and what is **UNVERIFIED** → [[ai-review]].
