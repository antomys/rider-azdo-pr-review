---
type: synthesis
title: "Plan vs. implementation (0.2.0)"
tags: [product, open-question]
sources: [src-plan, src-features-f1-f5, src-product-baseline]
created: 2026-10-20
updated: 2026-10-21
---
# Plan vs. implementation (0.2.0)

`docs/plan.md` predates the UI, so parts of it are outdated. Current state:

| Plan said | Now |
|---|---|
| Votes UI out of MVP | Built: Submit Review with every vote ([[voting]]) |
| Draft or batched reviews not available | Built client-side: in-memory drafts ([[draft-review]]) |
| Merge, close out of MVP | Built: ⋮ Merge…, Close Pull Request (not run live) |
| Threads panel | Replaced by the timeline editor tab |
| Unified diff threads | Built in 0.2.0 ([[unified-diff]]) |
| AI review (§5.2): consent dialog, findings previewed before posting | Built in 0.2.0 for the Claude Code CLI only ([[ai-review]]); Copilot CLI adapter not built |
| F1 sign-in | Built in 0.2.0 ([[sign-in]]) |
| F2 Assigned to me | Built in 0.2.0, direct teams only ([[assigned-to-me]]) |
| F3/F4 reviewed marking, update detection | Built in 0.2.0 ([[reviewed-marks]]) |
| F5 since-last-view view | Built in 0.2.0 ([[update-view]]) |
| Mentions via Graph + storagekeys | Built: project team members plus Graph subjectquery |
| `ReviewProvider` interface | Extended with `addReviewer`, `complete`, `abandon`, `avatar`, `searchUsers(repo, …)`; in 0.2.0 `organization`, `listChanges(…, compareTo)`, `listCommits`, `myTeams` |

Still not built: a second AI adapter (Copilot CLI); create pull request, checkout, likes, suggestion apply; Azure DevOps Server and OAuth sign-in. When building a planned item, re-check this table and the baseline's "Not built yet" list.

Sources: [[src-plan]], [[src-features-f1-f5]], [[src-product-baseline]]
