---
type: concept
title: "Change rules for agents"
tags: [process]
sources: [src-product-baseline, src-open-decisions]
created: 2026-10-20
updated: 2026-10-20
---
# Change rules for agents

1. **Product baseline:** `docs/product-baseline.md` describes approved behavior. Don't change, remove or restyle it without the owner's explicit request. Fixes toward it are fine; new features must not alter it. Update it in the same PR as an approved change.
2. **Attribution:** no AI or tool mentions and no `Co-Authored-By` trailers in commits, branch names or PR text (D19).
3. **Git:** never push to `master`; work on a branch and open a PR. Merging is the owner's call.
4. **Secrets:** tokens only in `PasswordSafe` (plugin) or the `AZDO_PAT` environment variable (scripts). Never print, log or commit them.
5. **Azure DevOps writes:** only to the [[sandbox]], recorded in the inventory.
6. **No AI calls:** the plugin makes none, and no code is sent to external services.
7. **Honesty:** state only what was verified; mark the rest **UNVERIFIED**.
8. **Code style:** match the surrounding code (KDoc on public types, short comments that explain why); keep changes surgical.

Sources: [[src-product-baseline]], [[src-open-decisions]]
