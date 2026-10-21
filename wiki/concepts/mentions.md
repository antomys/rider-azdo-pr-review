---
type: concept
title: "Mentions"
tags: [product, azure-devops]
sources: [src-product-baseline, src-engineering-notes, src-plan]
created: 2026-10-20
updated: 2026-10-20
---
# Mentions

- `CommentField` opens completion on `@`. Suggestions: participants first (author, the user, reviewers, comment authors), then `provider.searchUsers`, which combines cached project team members and Graph `subjectquery`. Either source may fail without hiding the other.
- Choosing a person inserts `@Name`. On send, `encode()` replaces each chosen `@Name` with `@<id>`. Drafts keep the name-to-person map, so a draft is encoded when it's sent.
- When reading comments back, `@<id>` is shown as `@Name`.

Sources: [[src-product-baseline]], [[src-engineering-notes]], [[src-plan]]
