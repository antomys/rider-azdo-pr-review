---
type: concept
title: "Assigned to me (F2)"
tags: [product, azure-devops]
sources: [src-product-baseline, src-engineering-notes, src-risks, src-features-f1-f5]
created: 2026-10-21
updated: 2026-10-21
---
# Assigned to me (F2)

A list preset showing the pull requests in the configured repository where the user is a reviewer directly or through one of their project teams. New in 0.2.0.

- **Code:** `review/Assignments.kt` (`AssignmentRole` REQUIRED/OPTIONAL/TEAM, `Assignment`, `AssignedReviews` with `complete`); `ReviewProvider.myTeams`; the preset and toggles in `toolwindow/PullRequestListPanel.kt` and `PullRequestListViewModel.kt` (`ListFilters`).
- **Query:** `teams?$mine=true` gives the user's direct teams. `searchCriteria.reviewerId` doesn't expand teams, so the plugin queries the user's id and each team id and merges by `pullRequestId`. A team query that fails is skipped and the list shows "Some team assignments couldn't be loaded"; the user's own query must succeed. Direct teams only (D25), so nested or non-team groups can be missed (R11). See [[azure-devops-rest]].
- **List:** the Reviewer chip reads "Me and my teams ×". Line 2 adds the role (Required, Optional, "via team <name>"), the vote (the team's vote for a team-only assignment), "Declined" and "Needs review". Sorted needs review → updated since review → reviewed, newest first within each (see [[reviewed-marks]]). Declined PRs are hidden unless **Show declined** is on. See [[ui-structure]].
- **Verified** in the sandbox IDE on 2026-10-21 for the 0.2.0 baseline: it lists [[sandbox]] PRs 2, 3, 4, 5, 7 and 8 with roles and votes, including "via team". PR 4 is assigned only to a team and comes back only for the team's id.
- **UNVERIFIED:** the shape of `votedFor` in `reviewers[]` (not seen in the sandbox).

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-risks]], [[src-features-f1-f5]]
