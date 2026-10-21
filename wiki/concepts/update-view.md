---
type: concept
title: "Update banner and changes since last view (F5)"
tags: [product, azure-devops]
sources: [src-product-baseline, src-engineering-notes, src-features-f1-f5]
created: 2026-10-21
updated: 2026-10-21
---
# Update banner and changes since last view (F5)

What a reviewer sees when a pull request got new iterations since they last opened it. New in 0.2.0; it builds on the last-viewed iteration kept with [[reviewed-marks]].

- **Last viewed:** opening the `#<id>` tab records the latest iteration as viewed (`ReviewMarks.viewed`), so the next opening shows no banner unless another iteration arrives.
- **Update banner** (`toolwindow/PullRequestDetailsPanel.kt`, data from `PullRequestDetailsViewModel`), above the title:
  - bold "Updated: iteration N → M" and a × that dismisses it for this tab;
  - one grey line per newer iteration: "Iteration M · <date> · <reasons>";
  - "K new commit(s)" (up to 10, short id and first line) and "K file(s) changed" (up to 10 paths, then "and N more");
  - links **Show changes since last view** (disabled when no file changed) and **Show all changes**.
  - It survives refreshes until dismissed.
- **Changes since last view** (`diff/PullRequestDiff.kt`): a separate editor tab `PR #<id>: changes since iteration N` with only the files changed between iteration N and the latest; left is the file at iteration N's source commit, right at the latest. No inline threads, gutter or review toolbar, and showing a file doesn't set [[viewed-marks]], because threads are positioned against the merge-base diff. Opening it again replaces the previous one.
- **REST used** (see [[azure-devops-rest]]): `…/iterations/{id}/commits` (cumulative, newest first, pages with `top`/`skip` without `$`) and `…/iterations/{latest}/changes?$compareTo={earlier}` (only the delta).
- **Verified** in the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: with a mark at iteration 1, opening the PR showed the banner (1 new commit, 1 file) and the since-last-view diff showed only that file.
- **UNVERIFIED:** real pushes and force-pushes; how several iteration reasons are written; what `$compareTo` returns after a rebase or force-push (R10).

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-features-f1-f5]]
