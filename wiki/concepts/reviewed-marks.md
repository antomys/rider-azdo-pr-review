---
type: concept
title: "Reviewed marks (F3/F4)"
tags: [product, architecture]
sources: [src-product-baseline, src-engineering-notes, src-risks, src-features-f1-f5]
created: 2026-10-21
updated: 2026-10-21
---
# Reviewed marks (F3/F4)

A local, per-user record that a pull request was reviewed at a given iteration, plus the iteration the user last viewed. New in 0.2.0; behavior is set by `docs/product-baseline.md` ("Reviewed marks"). Not the same as per-file [[viewed-marks]].

- **Local only:** never sent to Azure DevOps, votes don't change, not synced across machines (R9). Stored by `settings/ReviewMarksStore.kt` in the IDE-level file `azure-devops-pr-review-reviews.xml` (app level, roaming disabled), keyed `{organization}/{repositoryId}/{pullRequestId}/{userId}`; the repository id comes from the list response's `repository.id`.
- **State model** (`review/ReviewMarks.kt`): a `ReviewMark` holds the reviewed iteration and source commit, the last viewed iteration, and, after an update cleared the mark, `updatedFrom`/`updatedTo` with the update's reasons. `ReviewStatus` is `NEEDS_REVIEW`, `UPDATED` or `REVIEWED`, the order [[assigned-to-me]] sorts by. Comparisons never use time; `touched` is only for pruning.
- **Marking:** **Mark as Reviewed** (list context menu or the details tab's ⋮ menu, via `toolwindow/MarkReviewed.kt`) reads the iterations and records the latest one and its source commit. If it's newer than the last viewed iteration, a dialog offers **Mark Anyway**, **Review Update** and Cancel.
- **Clearing rule (F4):** a mark is cleared when the pull request has a newer iteration or a different source commit (a retarget counts). The list compares `lastMergeSourceCommit` with the reviewed commit and fetches iterations only when they differ; opening the pull request always checks. A cleared mark shows **Updated: iteration N → M** with reasons (push, force-push, rebase, retarget) and the PR is listed again. See [[update-view]].
- **List effect:** reviewed PRs are hidden unless **Show reviewed** is on, then they carry a grey **Reviewed** badge. See [[ui-structure]].
- **Pruning:** records of completed or abandoned PRs untouched for 90 days are removed when the list loads.
- **Verified** in the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: marking hides the PR and survives an IDE restart; Show reviewed brings it back with the badge; a mark stored at iteration 1 shows "Updated: iteration 1 → 2 (push)".
- **UNVERIFIED:** behavior with real new pushes and force-pushes (the check above used a stored mark against an existing iteration 2); whether `lastMergeSourceCommit` lags while `mergeStatus` is `queued` (R10).

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-risks]], [[src-features-f1-f5]]
