---
type: synthesis
title: "Plan vs. implementation (0.1.0)"
tags: [product, open-question]
sources: [src-plan, src-features-f1-f5, src-product-baseline]
created: 2026-10-20
updated: 2026-10-20
---
# Plan vs. implementation (0.1.0)

`docs/plan.md` predates the UI, so parts of it are outdated. Current state:

| Plan said | 0.1.0 |
|---|---|
| Votes UI out of MVP | Built: Submit Review with every vote ([[voting]]) |
| Draft or batched reviews not available | Built client-side: in-memory drafts ([[draft-review]]) |
| Merge, close out of MVP | Built: ⋮ Merge…, Close Pull Request (not run live) |
| Threads panel | Replaced by the timeline editor tab |
| Unified diff threads | Not built |
| AI review (§5.2) | Not built |
| F1–F5 | Not built (specified in `docs/features/F1-F5.md`) |
| Mentions via Graph + storagekeys | Built: project team members plus Graph subjectquery |
| `ReviewProvider` interface | Extended with `addReviewer`, `complete`, `abandon`, `avatar`, `searchUsers(repo, …)` |

When building a planned item, re-check this table and the baseline's "Not in 0.1.0" list.

Sources: [[src-plan]], [[src-features-f1-f5]], [[src-product-baseline]]
