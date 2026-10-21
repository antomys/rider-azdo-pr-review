---
type: source
title: "Product baseline (0.1.0)"
tags: [product, process]
authors: [owner, agent]
raw: docs/product-baseline.md
ingested: 2026-10-20
created: 2026-10-20
updated: 2026-10-20
---
# Product baseline (0.1.0)

**The authority on current behavior.** Approved by the owner on 2026-10-20 after a sandbox review; release `v0.1.0`, commit `8fd864e`.

- **Rule:** don't change, remove or restyle anything it describes unless the owner asks. Bug fixes toward it are fine. New features must not alter it. Update the file in the same PR when the owner approves a change. See [[change-rules]].
- **Covers:**
  - settings, the Pull Requests list (search, chips, rows), the details tab (header, files tree, review status, buttons, ⋮ menu) and the timeline tab;
  - the diff (inline threads, gutter, toolbar), the comment box, drafts, threads and mentions;
  - the Submit Review popup and viewed marks.
- **Lists what's not built** (unified-diff threads, AI review, F1–F5, …). See [[plan-vs-implementation]].

Pages built on it: [[draft-review]], [[voting]], [[viewed-marks]], [[mentions]], [[ui-structure]].
