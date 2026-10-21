---
type: concept
title: "Voting and Submit Review"
tags: [product, azure-devops]
sources: [src-product-baseline, src-engineering-notes]
created: 2026-10-20
updated: 2026-10-20
---
# Voting and Submit Review

- **Votes:** 10 approve, 5 approve with suggestions, 0 none, −5 wait for author ("Request Changes"), −10 reject.
- **`suggestedVote()`:** "Approve with Suggestions" while the user has drafts or open threads they started.
- **Submit Review popup:** main approve button; drop-down with the other approve option and Reject; Request Changes; Comment (no vote). Draft PRs disable voting.
- **Provider `setVote`:** reads the PR first and sends the reviewer's current `isRequired` (see [[azure-devops-rest]]).
- **Details tab:** "Set Myself as Reviewer" until the user reviews (`addReviewer`: optional, vote 0).

Sources: [[src-product-baseline]], [[src-engineering-notes]]
