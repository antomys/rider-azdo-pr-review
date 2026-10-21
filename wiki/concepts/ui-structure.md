---
type: concept
title: "UI structure"
tags: [product, architecture]
sources: [src-product-baseline, src-engineering-notes]
created: 2026-10-20
updated: 2026-10-21
---
# UI structure

Where each surface lives in code (behavior: `docs/product-baseline.md`).

| Surface | Code |
|---|---|
| Pull Requests list, search, chips | `toolwindow/PullRequestListPanel.kt`, `PullRequestListViewModel.kt` (`ListFilters`) |
| Funnel: Assigned to me preset, Show reviewed / Show declined toggles ([[assigned-to-me]]) | `toolwindow/PullRequestListPanel.kt` |
| List context menu: Mark as Reviewed / Not Reviewed ([[reviewed-marks]]) | `toolwindow/PullRequestListPanel.kt`, `MarkReviewed.kt` |
| Sign-in panel ([[sign-in]]) | `toolwindow/SignInPanel.kt`, `SignInViewModel.kt`; shown by `PullRequestToolWindowFactory.kt` |
| `#id` details tab, review status, ⋮ menu (incl. Mark as Reviewed, Review with AI…) | `toolwindow/PullRequestDetailsPanel.kt` |
| Update banner ([[update-view]]) | `toolwindow/PullRequestDetailsPanel.kt` |
| Files tree (also the diff's files popup) | `toolwindow/ReviewFilesTree.kt` |
| Submit Review popup | `toolwindow/SubmitReviewPopup.kt` |
| Timeline editor tab | `timeline/TimelineFile.kt`, `TimelinePanel.kt`; registered as `fileEditorProvider` in `plugin.xml` |
| Diff tab, threads, gutter, drafts | `diff/PullRequestDiff.kt`, `ReviewDiffExtension.kt` (`diff.DiffExtension`) |
| Unified view threads and gutter ([[unified-diff]]) | `diff/ReviewDiffExtension.kt`, `UnifiedLines.kt` |
| Changes since last view tab ([[update-view]]) | `diff/PullRequestDiff.kt` |
| Diff toolbar | `diff/ReviewDiffActions.kt` |
| Comment box, threads, drafts | `ui/CommentComposer.kt`, `CommentField.kt`, `ThreadPanel.kt`, `DraftPanel.kt` |
| Opening a PR (tab and timeline) | `toolwindow/PullRequestToolWindowFactory.kt` |
| AI Review settings group | `settings/AzureDevOpsConfigurable.kt`, `AiReviewSettings.kt` |
| AI consent and "AI Review Findings" dialogs ([[ai-review]]) | `ai/AiReviewFlow.kt` |

See [[plugin-codebase]] and [[intellij-platform]].

Sources: [[src-product-baseline]], [[src-engineering-notes]]
