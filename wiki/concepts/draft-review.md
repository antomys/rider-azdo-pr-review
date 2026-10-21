---
type: concept
title: "Draft review flow"
tags: [product]
sources: [src-product-baseline]
created: 2026-10-20
updated: 2026-10-20
---
# Draft review flow

Azure DevOps has no draft comments, so the plugin keeps drafts **in memory** in `PullRequestDetailsViewModel.drafts`, as `DraftComment(id, anchor, replyTo, body, mentioned)`.

- **Where drafts are the default:** new threads and replies in the diff ("Save as Draft ▾", with "Post Now"). In the timeline, Comment posts immediately and Save as Draft is the alternative.
- **Editing:** a pending draft shows "Post now", a PENDING badge, edit and delete.
- **`submitReview(vote, comment)`** sends drafts in order, then the summary comment, then the vote. Each draft is removed once sent, so a failure leaves only the unsent ones.
- **Lifetime:** drafts are lost when the PR tab closes or the IDE restarts. That's the baseline; persisting them would be a new feature.

See [[voting]] and [[src-product-baseline]].

Sources: [[src-product-baseline]]
