---
type: concept
title: "UI structure"
tags: [product, architecture]
sources: [src-product-baseline]
created: 2026-10-20
updated: 2026-10-20
---
# UI structure

Where each surface lives in code (behavior: `docs/product-baseline.md`).

| Surface | Code |
|---|---|
| Pull Requests list, search, chips | `toolwindow/PullRequestListPanel.kt`, `PullRequestListViewModel.kt` (`ListFilters`) |
| `#id` details tab, review status, ⋮ menu | `toolwindow/PullRequestDetailsPanel.kt` |
| Files tree (also the diff's files popup) | `toolwindow/ReviewFilesTree.kt` |
| Submit Review popup | `toolwindow/SubmitReviewPopup.kt` |
| Timeline editor tab | `timeline/TimelineFile.kt`, `TimelinePanel.kt`; registered as `fileEditorProvider` in `plugin.xml` |
| Diff tab, threads, gutter, drafts | `diff/PullRequestDiff.kt`, `ReviewDiffExtension.kt` (`diff.DiffExtension`) |
| Diff toolbar | `diff/ReviewDiffActions.kt` |
| Comment box, threads, drafts | `ui/CommentComposer.kt`, `CommentField.kt`, `ThreadPanel.kt`, `DraftPanel.kt` |
| Opening a PR (tab and timeline) | `toolwindow/PullRequestToolWindowFactory.kt` |

See [[plugin-codebase]] and [[intellij-platform]].

Sources: [[src-product-baseline]]
